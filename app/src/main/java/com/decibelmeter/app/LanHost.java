package com.decibelmeter.app;

import java.util.Set;
import java.util.TreeSet;

/**
 * 一台被局域网扫描发现的设备：地址、开放端口，以及尽力补全的机器名、网卡地址与设备类型线索。
 *
 * 端口决定这台设备「能干什么」，名称与类型只是「它大概是什么」。老版本只盯 135/139/445/3389
 * 四个端口，四个全关着的设备（手机、音箱、IoT）因此根本不会出现在列表里；现在这些设备可以由
 * UDP 137 广播或 mDNS 报上来，成为「一个端口都没开」的仅名称条目。
 *
 * 类型线索走 mDNS 服务类型与 NetBIOS 角色后缀，没有走网卡地址前缀（OUI）：安卓上能拿到 MAC
 * 的通道只有 UDP 137，而它只有开了共享的 Windows/Samba 机器会应答——那些设备本来就已经由机器名
 * 说明了自己。 `/proc/net/arp` 从 Android 10 起被 SELinux 拦住，做不成通用来源，所以不值得为它
 * 背一张上兆的前缀表。
 *
 * 扫描是并发回填的：同一台设备可能先因端口被发现、后因名称或 mDNS 类型再更新一次，所以字段
 * 一律 volatile，集合用同步方法读写，界面层每次拿到的是同一个对象实例。
 */
public final class LanHost {

    public static final int PORT_RPC = 135;
    public static final int PORT_NETBIOS = 139;
    public static final int PORT_SMB = 445;
    public static final int PORT_RDP = 3389;

    public final String ip;
    public volatile String name;
    public volatile String mac;
    /** mDNS 报上来的服务类型（形如 `_airplay._tcp.`），比端口更能说明「这是什么设备」。 */
    public volatile String mdnsType;
    /**
     * NetBIOS 名称表里出现过对应角色后缀：<20> 文件与打印服务器、<1C> 域控制器、
     * <1B>/<1D> 主浏览器。这三条比「开着 445」更能把域控和普通办公机分开。
     */
    public volatile boolean nbnsFileServer;
    public volatile boolean nbnsDomainController;
    public volatile boolean nbnsBrowser;
    /** 用户收藏过：换一轮扫描、换一个网段都留在列表里。 */
    public volatile boolean favorite;
    /** 手动添加的设备，不是本轮扫出来的，列表里始终置顶。 */
    public volatile boolean manual;
    /** 本轮 TCP 探测覆盖过这个地址（不论有没有开放端口）；只靠名称发现、还没轮到探测的设备没有这个标记。 */
    public volatile boolean probed;
    /** 名称的优先级：用户备注最高，其次 NetBIOS，再次 mDNS，最后反向 DNS。 */
    private volatile int nameRank = Integer.MAX_VALUE;
    /** 当前网段的默认网关；界面上单独标成「网关 / 路由器」并置顶。 */
    public volatile boolean gateway;
    /** 本轮扫描真的看到了这台设备（用于「上次在线」与「新设备」判断）。 */
    public volatile boolean seen;
    /** 基线建立后才出现过的地址。 */
    public volatile boolean newDevice;
    /** 同一个 IP 上的 MAC 与已存记录不同：可能换了设备，旧凭据要先确认。 */
    public volatile boolean identityChanged;
    /** 最后一次在本轮扫描里看到的时间；0 表示没有记录。 */
    public volatile long lastSeen;
    /** 第一个成功端口的 TCP 握手耗时，0 表示还没测到。 */
    public volatile long rttMs;
    /** SSDP / mDNS 报上来的型号名，比「像什么」更具体。 */
    public volatile String model;
    /** SSDP 的 SERVER 头，常带厂商与固件线索。 */
    public volatile String vendor;
    /** SSDP 的 deviceType，例如 InternetGatewayDevice / MediaRenderer。 */
    public volatile String ssdpType;
    /** 服务指纹的一句话结论与完整明细，由「识别设备」写入。 */
    public volatile String fingerprint;
    public volatile String fingerprintDetail;
    /** mDNS 广告里声明的端口；没有经过 TCP 验证，单独存。 */
    public volatile int mdnsPort;
    private volatile int mdnsTypeRank = Integer.MAX_VALUE;

    private final Set<Integer> openPorts = new TreeSet<>();

    public LanHost(String ip) {
        this.ip = ip;
    }

    /** 同一网段内 IP 唯一，取末段做排序键，结果列表不会因为异步发现而乱跳。 */
    public int sortKey() {
        int dot = ip.lastIndexOf('.');
        try {
            return Integer.parseInt(ip.substring(dot + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public synchronized void addOpenPort(int port) {
        openPorts.add(port);
    }

    /** 复探前清空上一轮结论：设备睡一觉回来，端口不能还写着开放。 */
    public synchronized void resetPorts() {
        openPorts.clear();
    }

    public synchronized Set<Integer> openPorts() {
        return new TreeSet<>(openPorts);
    }

    public synchronized boolean hasPort(int port) {
        return openPorts.contains(port);
    }

    /** 只保留最快的一次握手耗时，避免后到的慢端口把结论覆盖掉。 */
    public synchronized void recordRtt(long millis) {
        if (millis > 0 && (rttMs == 0 || millis < rttMs)) {
            rttMs = millis;
        }
    }

    /**
     * mDNS 服务类型按「名字有多像机器名」排序，避免同一台设备的 _raop 实例名把
     * _smb/_workstation 报上来的主机名覆盖掉。
     */
    public synchronized boolean considerName(String candidate, int rank) {
        if (candidate == null || candidate.isEmpty() || rank >= nameRank) {
            return false;
        }
        name = candidate;
        nameRank = rank;
        return true;
    }

    /** 用户备注或已存记录里的名字：优先级最高，后续自动发现不再覆盖。 */
    public synchronized void pinName(String value) {
        if (value != null && !value.isEmpty()) {
            name = value;
            nameRank = 0;
        }
    }

    public synchronized boolean considerMdnsType(String type, int rank) {
        if (type == null || rank >= mdnsTypeRank) {
            return false;
        }
        mdnsType = type;
        mdnsTypeRank = rank;
        return true;
    }

    public synchronized boolean considerMdnsPort(int port) {
        if (port > 0 && mdnsPort == 0) {
            mdnsPort = port;
            return true;
        }
        return false;
    }

    /** 445 或 139 任一开放就认为可能提供 SMB 共享：只开 139 的老机器仍走 NetBIOS 承载的 SMB1。 */
    public synchronized boolean mayOfferSmb() {
        return openPorts.contains(PORT_SMB) || openPorts.contains(PORT_NETBIOS);
    }

    public synchronized boolean mayOfferRdp() {
        return openPorts.contains(PORT_RDP);
    }

    public String displayName() {
        String current = name;
        return current == null || current.isEmpty() ? ip : current;
    }

    /** 能不能直接拿来做点什么（浏览共享、远程桌面、开管理页），用于「只看可用设备」过滤。 */
    public boolean isUsable() {
        return mayOfferSmb() || mayOfferRdp() || !openPorts.isEmpty() || manual || favorite;
    }
}
