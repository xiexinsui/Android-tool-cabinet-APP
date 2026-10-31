package com.decibelmeter.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网扫描：把「谁在这个网段里」这件事拆成四路并行发现，再汇总成一份设备表。
 *
 * 四路各有不可替代的地方：
 * <ul>
 *   <li>TCP 端口探测——结论最硬，端口确实开着才能浏览共享或远程桌面；但一个地址要连好几次，
 *       而且所有端口都被防火墙拦住的设备在它眼里等于不存在。</li>
 *   <li>UDP 137 广播——一个包问遍整段，能拿到机器名、网卡地址和域角色，是「TCP 看不见但机器
 *       还在说话」时最省的一条路。</li>
 *   <li>mDNS（NSD）浏览——手机、音箱、电视、打印机、NAS 多数不开 SMB/RDP，只能靠它报上来。</li>
 *   <li>SSDP/UPnP——电视、音箱、打印机和 IoT 很多只认这条路，连 mDNS 都不发。</li>
 * </ul>
 *
 * 探测分两级：{@link LanServices.Preset#presence} 每个地址都探，{@link LanServices.Preset#extra}
 * 只对已应答的地址补探——一个网段里真正常开服务的通常只有个位数台，把几十个端口平摊到整段
 * 只是把超时等待乘以地址数，换不来多少信息。
 *
 * 回调全部发生在工作线程，由界面层自己切回主线程；同一台设备始终复用同一个 {@link LanHost}
 * 实例，后到的名称或端口是补充而不是替换。
 */
public final class LanScanner {

    public static final class Stats {
        public final int tcpHosts;
        public final int nbnsHosts;
        public final int mdnsHosts;
        public final int ssdpHosts;
        public final int totalHosts;
        public final int mdnsFailures;

        Stats(int tcpHosts, int nbnsHosts, int mdnsHosts, int ssdpHosts, int totalHosts,
              int mdnsFailures) {
            this.tcpHosts = tcpHosts;
            this.nbnsHosts = nbnsHosts;
            this.mdnsHosts = mdnsHosts;
            this.ssdpHosts = ssdpHosts;
            this.totalHosts = totalHosts;
            this.mdnsFailures = mdnsFailures;
        }
    }

    public interface Listener {
        void onProgress(int done, int total);

        /** 设备被发现或被补充了新信息（名称、角色、补探端口）时回调，同一实例可能回调多次。 */
        void onHost(LanHost host);

        /**
         * @param scanned 本轮探测次数（整轮是地址数，详扫是端口数）
         * @param stats   整轮结束时的发现统计，单台详扫为 null
         */
        void onFinished(int scanned, Stats stats);

        /** 单台探测在开始前失败（目前只有地址解析失败），不会再回调 onFinished。 */
        default void onError(String ip) {
        }
    }

    /**
     * 一个本机地址所在的 IPv4 网段。以前固定按 /24 扫，现在按链路真实前缀：
     * /23、/22 能扫全，/16 这种太大的一律退回本机所在 /24，避免一轮扫几万个地址。
     */
    public static final class Range {
        public final int network;
        public final int prefixLength;

        private Range(int network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        /** 手动输入的「网段前三段」按 /24 处理。 */
        public static Range fromPrefixText(String text) {
            long value = parseIpv4(text + ".0");
            return value < 0 ? null : new Range((int) value & maskOf(24), 24);
        }

        public static Range fromIpAndPrefix(String ip, int prefixLength) {
            long value = parseIpv4(ip);
            if (value < 0 || prefixLength < 0 || prefixLength > 32) {
                return null;
            }
            return new Range((int) value & maskOf(prefixLength), prefixLength);
        }

        /** 网段过大时只扫本机所在的 /24，返回实际要扫的范围。 */
        public Range effective(String selfIp, int maxHosts) {
            if (hostCount() <= maxHosts) {
                return this;
            }
            long self = parseIpv4(selfIp);
            int base = self >= 0 && contains(selfIp) ? (int) self : network;
            return new Range(base & maskOf(24), 24);
        }

        public String prefixText() {
            return octet((network >> 24) & 0xFF) + "."
                    + octet((network >> 16) & 0xFF) + "."
                    + octet((network >> 8) & 0xFF);
        }

        public String cidr() {
            return dotted(network) + "/" + prefixLength;
        }

        public String broadcastAddress() {
            return dotted(network | ~maskOf(prefixLength));
        }

        public long hostCount() {
            if (prefixLength >= 31) {
                return 1L << (32 - prefixLength);
            }
            return (1L << (32 - prefixLength)) - 2;
        }

        public boolean contains(String ip) {
            long value = parseIpv4(ip);
            return value >= 0 && (((int) value) & maskOf(prefixLength)) == network;
        }

        /**
         * 本段内除本机以外的所有地址。调用方应先 {@link #effective}，所以最多是一千多个。
         */
        public List<String> targets(String selfIp) {
            List<String> targets = new ArrayList<>();
            long first = network & 0xFFFFFFFFL;
            long last = (network | ~maskOf(prefixLength)) & 0xFFFFFFFFL;
            if (prefixLength < 31) {
                first++;
                last--;
            }
            for (long value = first; value <= last; value++) {
                String ip = dotted((int) value);
                if (!ip.equals(selfIp)) {
                    targets.add(ip);
                }
            }
            return targets;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Range)) {
                return false;
            }
            Range range = (Range) other;
            return network == range.network && prefixLength == range.prefixLength;
        }

        @Override
        public int hashCode() {
            return network * 31 + prefixLength;
        }

        private static int maskOf(int prefixLength) {
            return prefixLength == 0 ? 0 : 0xFFFFFFFF << (32 - prefixLength);
        }

        private static long parseIpv4(String text) {
            if (text == null) {
                return -1;
            }
            String[] parts = text.trim().split("\\.");
            if (parts.length != 4) {
                return -1;
            }
            long value = 0;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return -1;
                }
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c < '0' || c > '9') {
                        return -1;
                    }
                }
                int octet = Integer.parseInt(part);
                if (octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            }
            return value;
        }

        private static String dotted(int value) {
            return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                    + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
        }

        private static String octet(int value) {
            return Integer.toString(value);
        }
    }

    /** 一个本机地址所在的网段，多网卡（Wi-Fi + USB 网卡 + 热点）时会有多个。 */
    public static final class Segment {
        public final String ip;
        public final Range range;
        public final String interfaceName;
        /** Wi-Fi 接口优先排在前面；蜂窝网络根本不会被列出来（扫它只烧流量）。 */
        public final boolean wifi;
        public final String gateway;

        Segment(String ip, Range range, String interfaceName, boolean wifi, String gateway) {
            this.ip = ip;
            this.range = range;
            this.interfaceName = interfaceName;
            this.wifi = wifi;
            this.gateway = gateway;
        }

        public String displayName() {
            String name = interfaceName == null || interfaceName.isEmpty() ? ip : interfaceName;
            return range.cidr() + " · " + name;
        }
    }

    private static final int THREADS = 48;
    private static final int TCP_TIMEOUT_MS = 250;
    /** 详扫要连一千多个端口，超时必须是整轮扫描的一半左右，否则一分钟都跑不完。 */
    private static final int DETAIL_TIMEOUT_MS = 120;
    private static final int NAME_TIMEOUT_MS = 700;
    private static final int NBNS_WINDOW_MS = 1400;
    private static final int MDNS_WINDOW_MS = 3500;
    private static final int SSDP_WINDOW_MS = 1800;
    private static final int SSDP_MAX_WAIT_MS = 4500;
    /** 一个网段最多扫这么多地址；更宽的网段退回本机 /24。 */
    public static final int MAX_TARGETS = 1024;
    /** 进度回调节流：每个地址都刷一次界面，除了拖慢扫描没有任何好处。 */
    private static final int PROGRESS_STEP = 4;
    private static final int DETAIL_STEP = 64;

    private static final String[] NSD_TYPES = {
            "_workstation._tcp.", "_smb._tcp.", "_http._tcp.", "_printer._tcp.",
            "_ipp._tcp.", "_airplay._tcp.", "_raop._tcp.", "_googlecast._tcp.",
            "_afpovertcp._tcp.", "_ssh._tcp.", "_sftp-ssh._tcp.", "_uscan._tcp.",
            "_device-info._tcp.", "_companion-link._tcp.", "_hap._tcp.", "_matter._tcp.",
    };

    /** mDNS 解析出来的一条服务记录；同一 IP 的多个服务合并到一条。 */
    private static final class Seen {
        String name;
        int nameRank = Integer.MAX_VALUE;
        String type;
        int typeRank = Integer.MAX_VALUE;
        String model;
        String mac;
        int port;

        void merge(String serviceName, String serviceType, String serviceModel, String serviceMac,
                   int servicePort) {
            int rank = typeRank(serviceType);
            String cleaned = cleanMdnsName(serviceName);
            if (cleaned != null && rank < nameRank) {
                name = cleaned;
                nameRank = rank;
            }
            if (serviceType != null && rank < typeRank) {
                type = serviceType;
                typeRank = rank;
            }
            if (model == null && serviceModel != null) {
                model = serviceModel;
            }
            if (mac == null && serviceMac != null) {
                mac = serviceMac;
            }
            if (port == 0 && servicePort > 0) {
                port = servicePort;
            }
        }
    }

    private static final class ResolveTask {
        final NsdServiceInfo info;
        final int attempt;

        ResolveTask(NsdServiceInfo info, int attempt) {
            this.info = info;
            this.attempt = attempt;
        }
    }

    private final Context context;
    private final NsdManager nsd;
    private final Map<String, LanHost> found = new ConcurrentHashMap<>();
    private final Map<String, Seen> nsdFound = new ConcurrentHashMap<>();
    private final Set<String> ssdpFound = ConcurrentHashMap.newKeySet();
    private final List<NsdManager.DiscoveryListener> discoveryListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<ResolveTask> resolveQueue = new ArrayDeque<>();
    private final Map<String, Integer> resolveAttempts = new HashMap<>();
    private final AtomicInteger mdnsFailures = new AtomicInteger();
    private final AtomicInteger nbnsResponses = new AtomicInteger();
    private boolean resolving;
    private volatile Listener mdnsListener;

    private volatile boolean cancelled;
    private volatile String selfIp;
    private volatile Range range;
    private volatile long mdnsDeadline;
    private volatile long roundStart;
    private volatile CountDownLatch ssdpLatch;

    public LanScanner(Context context) {
        this.context = context.getApplicationContext();
        this.nsd = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
    }

    /** 本机当前的 IPv4 地址（首选 Wi-Fi/有线），取不到时返回 null，界面转为让用户手填网段。 */
    public static String localIpv4(Context context) {
        List<Segment> segments = localSegments(context);
        return segments.isEmpty() ? null : segments.get(0).ip;
    }

    /**
     * 本机所有值得扫描的网段：Wi-Fi 与有线排前面，蜂窝网络直接跳过——运营商 NAT 段里
     * 扫不出用户自己的设备，只会白跑一趟流量。网段按链路真实前缀，网关顺手取出来给界面置顶。
     */
    public static List<Segment> localSegments(Context context) {
        List<Segment> segments = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            // getAllNetworks() 在 API 33 被标为过时，但它仍是 minSdk 24 上唯一能列出全部网络的
            // 入口（新写法要先有 Network 才能问，而 Network 正是从这里来的）
            for (Network network : manager.getAllNetworks()) {
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) {
                    continue;
                }
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    continue;
                }
                boolean wifi = capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                String gateway = gatewayOf(properties);
                for (LinkAddress address : properties.getLinkAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) {
                        add(segments, seen, address.getAddress().getHostAddress(),
                                address.getPrefixLength(), properties.getInterfaceName(), wifi,
                                gateway);
                    }
                }
            }
        } catch (Exception e) {
            // 拿不到全部网络就退回枚举网卡的老办法
        }
        if (segments.isEmpty()) {
            String fallback = networkInterfaceAddress();
            if (fallback != null) {
                add(segments, seen, fallback, 24, "", false, null);
            }
        }
        List<Segment> wifi = new ArrayList<>();
        List<Segment> other = new ArrayList<>();
        for (Segment segment : segments) {
            (segment.wifi ? wifi : other).add(segment);
        }
        // Wi-Fi 优先，其次按接口发现的顺序，避免每次进来顺序都换
        wifi.addAll(other);
        return wifi;
    }

    private static String gatewayOf(LinkProperties properties) {
        String fallback = null;
        for (RouteInfo route : properties.getRoutes()) {
            if (!route.isDefaultRoute() || route.getGateway() == null) {
                continue;
            }
            // IPv6 默认路由常常排在前面；界面和 host.gateway 只认 IPv4 网关
            if (route.getGateway() instanceof Inet4Address) {
                return route.getGateway().getHostAddress();
            }
            if (fallback == null) {
                fallback = route.getGateway().getHostAddress();
            }
        }
        return fallback;
    }

    private static void add(List<Segment> segments, Set<String> seen, String ip, int prefixLength,
                            String interfaceName, boolean wifi, String gateway) {
        Range candidate = Range.fromIpAndPrefix(ip, prefixLength);
        if (candidate == null || !seen.add(candidate.cidr())) {
            return;
        }
        segments.add(new Segment(ip, candidate, interfaceName, wifi, gateway));
    }

    /**
     * 整轮扫描：TCP 两级探测 + NBNS 广播 + mDNS 浏览 + SSDP 同时跑，全部收完才回调 onFinished。
     *
     * 广播和 SSDP 任务排在探测任务前面，为的是不被 48 条线程的队列饿死。收尾时再等 mDNS 与
     * SSDP 的窗口过完，让「只回名称的设备」赶在完成提示之前进列表。
     */
    public void start(final Range requestedRange, final String selfIp,
                      final LanServices.Preset preset, final Listener listener) {
        cancelled = false;
        this.selfIp = selfIp;
        this.range = requestedRange.effective(selfIp, MAX_TARGETS);
        this.mdnsDeadline = System.currentTimeMillis() + MDNS_WINDOW_MS;
        this.roundStart = System.currentTimeMillis();
        found.clear();
        nsdFound.clear();
        ssdpFound.clear();
        nbnsResponses.set(0);
        mdnsFailures.set(0);
        resetResolveQueue();

        final List<String> targets = this.range.targets(selfIp);
        final int total = targets.size();
        if (total == 0) {
            listener.onFinished(0, new Stats(0, 0, 0, 0, 0, 0));
            return;
        }
        final AtomicInteger done = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        ssdpLatch = latch;
        browseNames(listener);

        pool.execute(() -> {
            try {
                SsdpDiscovery.browse(context, SSDP_WINDOW_MS,
                        (ip, info) -> applySsdp(ip, info, listener));
            } finally {
                latch.countDown();
            }
        });
        pool.execute(() -> nbnsResponses.set(NetBios.enumerate(
                this.range.broadcastAddress(), NBNS_WINDOW_MS,
                (ip, info) -> applyNbns(ip, info, listener))));

        for (final String ip : targets) {
            pool.execute(() -> {
                if (!cancelled) {
                    probe(ip, preset, listener);
                }
                int completed = done.incrementAndGet();
                if (completed % PROGRESS_STEP == 0 || completed == total) {
                    listener.onProgress(completed, total);
                }
                if (completed == total) {
                    finishRound(total, listener);
                }
            });
        }
    }

    /**
     * 单台设备的端口详扫：1–1024 连续段加常见高位服务端口，用于回答「这台到底是什么」。
     * 复用同一个线程池，一千多个端口在 120 ms 超时下大约三秒结束。
     */
    public void startDetail(final String ip, final int[] ports, final Listener listener) {
        cancelled = false;
        this.range = null;
        this.selfIp = null;
        final LanHost host = obtain(ip);
        final AtomicInteger done = new AtomicInteger();
        final int total = ports.length;
        // 主机名解析可能是一次 DNS 查询，不能放在调用方（界面线程）里做。解析成功后再清空
        // 上一轮端口：解析失败时原结论还在，用户改对名字重探即可。
        pool.execute(() -> {
            final InetAddress address;
            try {
                address = InetAddress.getByName(ip);
            } catch (Exception e) {
                listener.onError(ip);
                return;
            }
            host.resetPorts();
            host.probed = false;
            pool.execute(() -> applyNbnsUnicast(host, listener));
            for (final int port : ports) {
                pool.execute(() -> {
                    long rtt = connectTime(address, port, DETAIL_TIMEOUT_MS);
                    if (!cancelled && rtt >= 0) {
                        host.addOpenPort(port);
                        host.recordRtt(rtt);
                    }
                    int completed = done.incrementAndGet();
                    if (completed % DETAIL_STEP == 0 || completed == total) {
                        listener.onProgress(completed, total);
                    }
                    if (completed == total) {
                        // 端口全关也是「探过了」的结论，否则界面会退回「还没探测」
                        host.probed = true;
                        listener.onHost(host);
                        listener.onFinished(total, null);
                    }
                });
            }
        });
    }

    public void cancel() {
        cancelled = true;
    }

    /** 页面销毁时调用：取消排队任务并断开 mDNS 浏览，否则 NsdManager 会泄漏注册。 */
    public void shutdown() {
        cancelled = true;
        stopBrowse();
        pool.shutdownNow();
    }

    private void finishRound(int total, Listener listener) {
        if (!cancelled) {
            long left = mdnsDeadline - System.currentTimeMillis();
            if (left > 0) {
                try {
                    Thread.sleep(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        CountDownLatch latch = ssdpLatch;
        if (!cancelled && latch != null) {
            long remaining = SSDP_MAX_WAIT_MS - (System.currentTimeMillis() - roundStart);
            if (remaining > 0) {
                try {
                    latch.await(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        stopBrowse();
        publishNameOnly(listener, !cancelled);
        listener.onFinished(total, stats());
    }

    private Stats stats() {
        int tcp = 0;
        for (LanHost host : found.values()) {
            if (!host.openPorts().isEmpty()) {
                tcp++;
            }
        }
        return new Stats(tcp, nbnsResponses.get(), nsdFound.size(), ssdpFound.size(),
                found.size(), mdnsFailures.get());
    }

    /**
     * 探测一个地址：先探在场端口，一个都没开就直接收工——名称由广播、mDNS 与 SSDP 负责，
     * 不值得在这里为一个大概率空闲的地址付反向 DNS 的等待时间。
     */
    private void probe(String ip, LanServices.Preset preset, Listener listener) {
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (Exception e) {
            return;
        }
        LanHost host = null;
        for (int port : preset.presence) {
            if (cancelled) {
                return;
            }
            long rtt = connectTime(address, port, TCP_TIMEOUT_MS);
            if (rtt >= 0) {
                if (host == null) {
                    host = obtain(ip);
                }
                host.addOpenPort(port);
                host.recordRtt(rtt);
                host.probed = true;
            }
        }
        if (host == null) {
            // 一个在场端口都没开。如果这台先前已经由广播或 mDNS 报上来，要把它从「还没探」改成
            // 「探过了、确实全关着」——这两种状态对用户是完全不同的结论
            LanHost known = found.get(ip);
            if (known != null && !known.probed) {
                known.probed = true;
                listener.onHost(known);
            }
            return;
        }
        listener.onHost(host);
        for (int port : preset.extra) {
            if (cancelled) {
                return;
            }
            long rtt = connectTime(address, port, TCP_TIMEOUT_MS);
            if (rtt >= 0) {
                host.addOpenPort(port);
                host.recordRtt(rtt);
            }
        }
        if (preset.extra.length > 0) {
            listener.onHost(host);
        }
        if (fillName(host, address)) {
            listener.onHost(host);
        }
    }

    /** NetBIOS → mDNS → 反向 DNS 依次尝试，补到名称返回 true。 */
    private boolean fillName(LanHost host, InetAddress address) {
        NetBios.NameInfo info = NetBios.query(address, NAME_TIMEOUT_MS);
        if (info != null) {
            applyNbnsInfo(host, info);
        }
        if (host.name != null) {
            return true;
        }
        Seen browsed = nsdFound.get(host.ip);
        if (browsed != null) {
            boolean changed = host.considerName(browsed.name, browsed.nameRank + 2);
            if (host.considerMdnsType(browsed.type, browsed.typeRank)) {
                changed = true;
            }
            if (host.considerMdnsType(browsed.type, browsed.typeRank)) {
                changed = true;
            }
            if (host.model == null && browsed.model != null) {
                host.model = browsed.model;
                changed = true;
            }
            if (host.mac == null && browsed.mac != null) {
                host.mac = browsed.mac;
                changed = true;
            }
            if (host.considerMdnsPort(browsed.port)) {
                changed = true;
            }
            if (changed) {
                return true;
            }
        }
        try {
            String canonical = address.getCanonicalHostName();
            if (canonical != null && !canonical.equals(host.ip) && canonical.length() <= 64) {
                return host.considerName(canonical, 6);
            }
        } catch (Exception e) {
            // 没有 PTR 记录时系统本来就直接回 IP，异常同样只当作「查不到名字」
        }
        return false;
    }

    /** 详扫时单播问一次名字：广播窗口早过了，而这台是用户点名要查的。 */
    private void applyNbnsUnicast(LanHost host, Listener listener) {
        try {
            NetBios.NameInfo info = NetBios.query(InetAddress.getByName(host.ip), NAME_TIMEOUT_MS);
            if (info != null && applyNbnsInfo(host, info)) {
                listener.onHost(host);
            }
        } catch (Exception e) {
            // 名字查不到不影响端口结论
        }
    }

    private boolean applyNbnsInfo(LanHost host, NetBios.NameInfo info) {
        boolean changed = host.considerName(info.workstation, 1);
        if (host.mac == null && info.mac != null) {
            host.mac = info.mac;
            changed = true;
        }
        if (info.fileServer && !host.nbnsFileServer) {
            host.nbnsFileServer = true;
            changed = true;
        }
        if (info.domainController && !host.nbnsDomainController) {
            host.nbnsDomainController = true;
            changed = true;
        }
        if (info.browser && !host.nbnsBrowser) {
            host.nbnsBrowser = true;
            changed = true;
        }
        return changed;
    }

    /**
     * 广播应答里带回来的设备：即使一个端口都没探开也要列出来，用户找的可能正是这种
     * 「开着共享却被防火墙拦得死死的」机器。
     */
    private void applyNbns(String ip, NetBios.NameInfo info, Listener listener) {
        if (!inSegment(ip) || ip.equals(selfIp)) {
            return;
        }
        LanHost host = obtain(ip);
        if (applyNbnsInfo(host, info)) {
            listener.onHost(host);
        }
    }

    private void applySsdp(String ip, SsdpDiscovery.Info info, Listener listener) {
        if (!inSegment(ip) || ip.equals(selfIp)) {
            return;
        }
        ssdpFound.add(ip);
        boolean changed = false;
        LanHost host = obtain(ip);
        if (host.considerName(cleanMdnsName(info.name), 3)) {
            changed = true;
        }
        if (info.model != null && host.model == null) {
            host.model = info.model;
            changed = true;
        }
        if (info.mac != null && host.mac == null) {
            host.mac = info.mac;
            changed = true;
        }
        if (info.vendor != null && host.vendor == null) {
            host.vendor = info.vendor;
            changed = true;
        }
        if (info.deviceType != null && host.ssdpType == null) {
            host.ssdpType = info.deviceType;
            changed = true;
        }
        if (changed) {
            listener.onHost(host);
        }
    }

    /**
     * mDNS 报上来的设备：端口没开也值得占一行，类型线索全靠它。
     *
     * @param probedRound 整轮 TCP 是否真的跑完了。跑完了才可以说这些设备「探过、全关着」；
     *                    中途被停止时探测并没有覆盖到每个地址，那时它们仍然只是「听说过」。
     */
    private void publishNameOnly(Listener listener, boolean probedRound) {
        for (Map.Entry<String, Seen> entry : nsdFound.entrySet()) {
            String ip = entry.getKey();
            if (!inSegment(ip) || ip.equals(selfIp)) {
                continue;
            }
            LanHost host = obtain(ip);
            Seen value = entry.getValue();
            boolean changed = host.considerName(value.name, value.nameRank + 2);
            if (host.considerMdnsType(value.type, value.typeRank)) {
                changed = true;
            }
            if (host.model == null && value.model != null) {
                host.model = value.model;
                changed = true;
            }
            if (host.mac == null && value.mac != null) {
                host.mac = value.mac;
                changed = true;
            }
            if (host.considerMdnsPort(value.port)) {
                changed = true;
            }
            if (probedRound && !host.probed) {
                host.probed = true;
                changed = true;
            }
            if (changed) {
                listener.onHost(host);
            }
        }
    }

    private boolean inSegment(String ip) {
        Range current = range;
        return current != null && current.contains(ip);
    }

    private LanHost obtain(String ip) {
        LanHost host = found.get(ip);
        if (host == null) {
            LanHost created = new LanHost(ip);
            LanHost existing = found.putIfAbsent(ip, created);
            host = existing != null ? existing : created;
        }
        return host;
    }

    private static long connectTime(InetAddress address, int port, int timeoutMillis) {
        Socket socket = new Socket();
        long started = System.nanoTime();
        try {
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
            return Math.max(1, (System.nanoTime() - started) / 1_000_000);
        } catch (Exception e) {
            // 端口关闭或主机不回 SYN，都是扫描的正常结论
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // 探测用的连接断开失败没有后果
            }
        }
    }

    /** 枚举网卡兜底：少数机型上 ConnectivityManager 拿不到链路属性，但 java.net 还能看到地址。 */
    private static String networkInterfaceAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) {
                        return address.getAddress().getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            return null;
        }
        return null;
    }

    private void browseNames(final Listener listener) {
        if (nsd == null) {
            return;
        }
        mdnsListener = listener;
        for (String type : NSD_TYPES) {
            NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    mainHandler.post(() -> enqueueResolve(serviceInfo));
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
                discoveryListeners.add(discovery);
            } catch (Exception e) {
                // 少数机型没有 mDNS 栈，名称还有 NetBIOS、SSDP 与反向 DNS 三条兜底
            }
        }
    }

    /**
     * NsdManager 的 resolveService 在不少机型上不允许并发，失败还会静默丢掉整台设备。
     * 这里排成单队列逐个解析，失败重试一次；并发发现仍然照旧。
     */
    private void enqueueResolve(NsdServiceInfo info) {
        if (cancelled || nsd == null) {
            return;
        }
        resolveQueue.add(new ResolveTask(info, 0));
        resolveNext();
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null || cancelled) {
            return;
        }
        final ResolveTask task = resolveQueue.poll();
        resolving = true;
        final String key = task.info.getServiceName() + "|" + task.info.getServiceType();
        try {
            nsd.resolveService(task.info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    mdnsFailures.incrementAndGet();
                    if (task.attempt == 0 && !resolveAttempts.containsKey(key)) {
                        resolveAttempts.put(key, 1);
                        resolveQueue.add(new ResolveTask(serviceInfo, 1));
                    }
                    finishResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    handleResolved(serviceInfo);
                    finishResolve();
                }
            });
        } catch (Exception e) {
            mdnsFailures.incrementAndGet();
            resolving = false;
            mainHandler.post(this::resolveNext);
        }
    }

    private void finishResolve() {
        resolving = false;
        mainHandler.post(this::resolveNext);
    }

    private void resetResolveQueue() {
        resolveQueue.clear();
        resolveAttempts.clear();
        resolving = false;
    }

    private void handleResolved(NsdServiceInfo serviceInfo) {
        if (cancelled) {
            return;
        }
        final String serviceName = serviceInfo.getServiceName();
        final String type = serviceInfo.getServiceType();
        if (serviceName == null || type == null) {
            return;
        }
        final int port = serviceInfo.getPort();
        final String model = attribute(serviceInfo, "model", "md", "ty", "usb_MDL");
        final String mac = macAttribute(serviceInfo);
        // NSD 返回的地址对象往往只有主机名、还没解析，取地址要在后台做。
        // getHost() 已被标为过时，替代的 getHostAddresses() 在部分 SDK 上属于
        // T 扩展能力，minSdk 24 上不能无条件调用，所以仍走这条通用路径。
        final String hostName = serviceInfo.getHost() == null
                ? null : serviceInfo.getHost().getHostName();
        if (hostName == null) {
            return;
        }
        final Listener listener = mdnsListener;
        if (listener == null) {
            return;
        }
        pool.execute(() -> {
            try {
                remember(serviceName, type, model, mac, port,
                        Arrays.asList(InetAddress.getAllByName(hostName)), listener);
            } catch (Exception e) {
                // 解析不出地址就没有这条 mDNS 名称
            }
        });
    }

    /** AirPlay/RAOP 的 deviceid 往往是 MAC；部分 NAS 会在 TXT 里直接给 mac。 */
    private static String macAttribute(NsdServiceInfo service) {
        Map<String, byte[]> attributes = service.getAttributes();
        if (attributes == null) {
            return null;
        }
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            String lower = key.toLowerCase(java.util.Locale.US);
            if (!lower.equals("deviceid") && !lower.equals("device-id")
                    && !lower.equals("mac") && !lower.equals("macaddress")
                    && !lower.equals("hwaddr") && !lower.equals("hardwareaddress")) {
                continue;
            }
            String mac = LanMac.normalizeOrNullAllZero(
                    new String(entry.getValue(), StandardCharsets.UTF_8));
            if (mac != null) {
                return mac;
            }
        }
        return null;
    }

    private static String attribute(NsdServiceInfo service, String... names) {
        Map<String, byte[]> attributes = service.getAttributes();
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            for (String name : names) {
                if (key.equalsIgnoreCase(name)) {
                    String value = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
                    if (!value.isEmpty() && !value.matches("[0-9,; ]+")) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Windows 的 SMB/WSD 会报「主机名: 用户@域名:」，AirPlay/RAOP 会报「MAC@设备名」。
     * 这些都不是人想看的名字，能可靠剥掉的就剥掉。
     */
    private static String cleanMdnsName(String value) {
        if (value == null) {
            return null;
        }
        String name = value.trim();
        int at = name.indexOf('@');
        if (at > 0 && name.substring(0, at).matches("(?i)[0-9a-f]{12}")) {
            String tail = name.substring(at + 1).trim();
            return tail.isEmpty() ? name : tail;
        }
        int colon = name.indexOf(':');
        if (colon > 0 && at > colon) {
            String head = name.substring(0, colon).trim();
            if (head.matches("[A-Za-z0-9._-]{1,63}")) {
                return head;
            }
        }
        return name;
    }

    /** mDNS 服务类型的名称优先级：越像机器名的越优先。 */
    private static int typeRank(String type) {
        if (type == null) {
            return Integer.MAX_VALUE;
        }
        String lower = type.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("_workstation") || lower.startsWith("_smb")) {
            return 0;
        }
        if (lower.startsWith("_device-info") || lower.startsWith("_http")) {
            return 1;
        }
        if (lower.startsWith("_printer") || lower.startsWith("_ipp")) {
            return 2;
        }
        if (lower.startsWith("_airplay") || lower.startsWith("_raop")) {
            return 3;
        }
        return 4;
    }

    /** 把一台 mDNS 报上来的设备的 IPv4 地址记进名称表，并顺手补到已经发现的设备上。 */
    private void remember(String serviceName, String type, String model, String mac, int port,
                          List<InetAddress> addresses, Listener listener) {
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet4Address)) {
                continue;
            }
            String ip = address.getHostAddress();
            if (!inSegment(ip)) {
                continue;
            }
            Seen seen = nsdFound.get(ip);
            if (seen == null) {
                seen = new Seen();
                Seen existing = nsdFound.putIfAbsent(ip, seen);
                if (existing != null) {
                    seen = existing;
                }
            }
            synchronized (seen) {
                seen.merge(serviceName, type, model, mac, port);
            }
            // 已经扫到过这台就立刻补名称；还没扫到则等收尾时统一放出来
            LanHost host = found.get(ip);
            if (host != null && inSegment(ip)) {
                boolean changed = host.considerName(seen.name, seen.nameRank + 2);
                if (host.considerMdnsType(seen.type, seen.typeRank)) {
                    changed = true;
                }
                if (host.model == null && seen.model != null) {
                    host.model = seen.model;
                    changed = true;
                }
                if (host.mac == null && seen.mac != null) {
                    host.mac = seen.mac;
                    changed = true;
                }
                if (host.considerMdnsPort(seen.port)) {
                    changed = true;
                }
                if (changed) {
                    listener.onHost(host);
                }
            }
        }
    }

    private void stopBrowse() {
        mdnsListener = null;
        // 队列只在主线程上动；finishRound 可能在工作线程调用这里
        mainHandler.post(this::resetResolveQueue);
        if (nsd == null) {
            return;
        }
        for (NsdManager.DiscoveryListener listener : discoveryListeners) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception e) {
                // 本来就没注册成功
            }
        }
        discoveryListeners.clear();
    }
}
