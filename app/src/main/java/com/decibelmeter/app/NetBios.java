package com.decibelmeter.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetBIOS 名称服务查询（UDP 137 的 Adapter Status），用来取 Windows 机器的名字、网卡地址
 * 和它承担的角色。
 *
 * 名称按 RFC 1002 写成 RFC 1035 的压缩标签：16 字节名（15 字节名字 + 1 字节后缀）每字节拆成
 * 高低半字节各加 'A'，扩成 32 个可见字符，标签长度字段写 0x20。状态查询用通配名（15 个 '*' +
 * 后缀 0x00），编码后即 "CK"*15 + "AA"。
 *
 * 两种用法各有必要：单播查询配合 TCP 探测使用，只问已经应答过的地址，代价可控；广播查询一个
 * 包问遍整段，是「防火墙把 TCP 四个端口全拦住但机器还在说话」时唯一还能报出名字的办法。
 *
 * 响应的名称表按后缀区分角色：{@code <00>} 工作站名、{@code <20>} 文件/打印服务器、
 * {@code <1B>} 域主浏览器、{@code <1C>} 域控制器、{@code <1D>} 主浏览器。
 */
final class NetBios {

    private static final int PORT = 137;
    private static final int MAX_RESPONSE = 2048;
    private static final int HEADER = 12;
    private static final int FLAG_RESPONSE = 0x8000;
    private static final int FLAG_RECURSION = 0x0100;
    private static final int TYPE_NBSTAT = 0x0021;
    private static final int CLASS_IN = 0x0001;
    private static final int SUFFIX_WORKSTATION = 0x00;
    private static final int SUFFIX_FILE_SERVER = 0x20;
    private static final int SUFFIX_DOMAIN_MASTER = 0x1B;
    private static final int SUFFIX_DOMAIN_CONTROLLER = 0x1C;
    private static final int SUFFIX_MASTER_BROWSER = 0x1D;
    /** RFC 1002 名称表项：15 字节名字 + 1 字节后缀 + 2 字节 flags。 */
    private static final int NAME_ENTRY = 18;
    private static final int MAC_LENGTH = 6;

    private static final AtomicInteger QUERY_ID = new AtomicInteger();

    static final class NameInfo {
        final String workstation;
        final String mac;
        /** 名称表里出现过对应的角色后缀：能据此把「一台 Windows」和「一台域控」分开写。 */
        final boolean fileServer;
        final boolean domainController;
        final boolean browser;

        NameInfo(String workstation, String mac, boolean fileServer, boolean domainController,
                 boolean browser) {
            this.workstation = workstation;
            this.mac = mac;
            this.fileServer = fileServer;
            this.domainController = domainController;
            this.browser = browser;
        }

        /** 一点信息都没有就没必要往列表里塞。 */
        boolean hasAnything() {
            return workstation != null || mac != null || fileServer || domainController || browser;
        }
    }

    /** 广播查询的接收端：每个应答的适配器回调一次。 */
    interface Sink {
        void onAdapter(String ip, NameInfo info);
    }

    private NetBios() {
    }

    /**
     * 超时、丢包、目标防火墙拦 UDP 137 都返回 null 而不抛异常：机器名只是补充信息，
     * 扫到开放端口才是硬结论，不值得为它打断整轮扫描。
     */
    static NameInfo query(InetAddress address, int timeoutMillis) {
        DatagramSocket socket = null;
        try {
            int id = QUERY_ID.incrementAndGet() & 0xFFFF;
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMillis);
            byte[] request = buildQuery(id);
            socket.send(new DatagramPacket(request, request.length, address, PORT));
            byte[] buffer = new byte[MAX_RESPONSE];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return parse(buffer, response.getLength(), id);
        } catch (IOException e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /**
     * 向网段的广播地址发一次通配 Adapter Status，然后在截止时间内持续收应答：每台应答的
     * 机器各回一个包，源地址就是它的 IP，这正是广播枚举比逐地址单播快两个数量级的原因。
     *
     * 有些设备只回第一个包之后的重传窗口，所以再发一次查询、并把收包截止时间独立于单包超时。
     * 返回收到多少台不同地址的应答；访问点隔离（AP isolation）会把广播吞掉，那时这里是 0，
     * 属于正常结果而不是错误。
     */
    static int enumerate(String broadcastAddress, int timeoutMillis, Sink sink) {
        DatagramSocket socket = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            int id = QUERY_ID.incrementAndGet() & 0xFFFF;
            byte[] request = buildQuery(id);
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            InetAddress target = InetAddress.getByName(broadcastAddress);
            socket.send(new DatagramPacket(request, request.length, target, PORT));
            // 截止时间以「现在开始 + timeoutMillis」为准，单包超时取剩余时间，保证总耗时可控
            long deadline = System.currentTimeMillis() + timeoutMillis;
            byte[] buffer = new byte[MAX_RESPONSE];
            int secondRoundAt = timeoutMillis / 2;
            boolean resent = false;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                if (!resent && left <= timeoutMillis - secondRoundAt) {
                    // 半程还没收到任何应答时重发一次，覆盖第一包丢掉的情况
                    resent = true;
                    socket.send(new DatagramPacket(request, request.length, target, PORT));
                }
                socket.setSoTimeout((int) Math.min(left, 1000));
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                NameInfo info = parse(buffer, response.getLength(), id);
                if (info == null || !info.hasAnything()) {
                    continue;
                }
                String ip = response.getAddress().getHostAddress();
                if (seen.add(ip)) {
                    sink.onAdapter(ip, info);
                }
            }
        } catch (IOException e) {
            // 一个应答都没收到、socket 建不起来、网段拼不出广播地址都归到这里
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return seen.size();
    }

    private static byte[] buildQuery(int id) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write16(out, id);
        write16(out, FLAG_RECURSION);
        write16(out, 1);
        write16(out, 0);
        write16(out, 0);
        write16(out, 0);
        out.write(0x20);
        for (int i = 0; i < 15; i++) {
            writeHalfBytes(out, '*');
        }
        writeHalfBytes(out, 0);
        out.write(0);
        write16(out, TYPE_NBSTAT);
        write16(out, CLASS_IN);
        return out.toByteArray();
    }

    private static NameInfo parse(byte[] data, int length, int id) {
        if (length < HEADER + 4 || read16(data, 0) != id) {
            return null;
        }
        if ((read16(data, 2) & FLAG_RESPONSE) == 0) {
            return null;
        }
        // DNS/NBSTAT 头：QDCOUNT=4、ANCOUNT=6；之前读成 6/8，名称表和统计区从来没被解析到
        int questions = read16(data, 4);
        int answers = read16(data, 6);

        int pos = HEADER;
        for (int i = 0; i < questions; i++) {
            pos = skipLabels(data, pos, length) + 4;
        }

        Names names = new Names();
        for (int i = 0; i < answers; i++) {
            pos = skipLabels(data, pos, length);
            if (pos + 10 > length) {
                break;
            }
            int type = read16(data, pos);
            int dataLength = read16(data, pos + 8);
            pos += 10;
            if (dataLength < 0 || pos + dataLength > length) {
                break;
            }
            if (type == TYPE_NBSTAT) {
                parseNameTable(data, pos, dataLength, names);
            }
            pos += dataLength;
        }
        NameInfo info = names.toInfo();
        // 只回了个空名称表时返回 null，让调用方继续走 mDNS / 反向 DNS 的兜底
        return info.hasAnything() ? info : null;
    }

    private static void parseNameTable(byte[] data, int offset, int length, Names out) {
        int count = data[offset] & 0xFF;
        int end = offset + length;
        int pos = offset + 1;
        for (int i = 0; i < count && pos + NAME_ENTRY <= end; i++) {
            int suffix = data[pos + 15] & 0xFF;
            switch (suffix) {
                case SUFFIX_WORKSTATION:
                    if (out.workstation == null) {
                        out.workstation = decodeName(data, pos);
                    }
                    break;
                case SUFFIX_FILE_SERVER:
                    out.fileServer = true;
                    break;
                case SUFFIX_DOMAIN_CONTROLLER:
                    out.domainController = true;
                    break;
                case SUFFIX_DOMAIN_MASTER:
                case SUFFIX_MASTER_BROWSER:
                    out.browser = true;
                    break;
                default:
                    break;
            }
            pos += NAME_ENTRY;
        }
        // 名称表之后是统计区，前 6 字节就是网卡地址（Unit ID）；以前把这里当成名称条目读，
        // 所以即使设备回应了 NBSTAT 也永远拿不到 MAC。
        if (out.mac == null && pos + MAC_LENGTH <= end) {
            out.mac = formatMac(data, pos);
        }
    }

    /** 16 字节名的前 15 字节是名字，控制字符按空格处理后再裁首尾空白。 */
    private static String decodeName(byte[] data, int offset) {
        StringBuilder builder = new StringBuilder(15);
        for (int i = 0; i < 15; i++) {
            int value = data[offset + i] & 0xFF;
            builder.append(value >= 0x20 && value < 0x7F ? (char) value : ' ');
        }
        String name = builder.toString().trim();
        return name.isEmpty() ? null : name;
    }

    private static String formatMac(byte[] data, int offset) {
        StringBuilder builder = new StringBuilder(17);
        boolean allZero = true;
        for (int i = 0; i < MAC_LENGTH; i++) {
            int value = data[offset + i] & 0xFF;
            if (value != 0) {
                allZero = false;
            }
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format(Locale.US, "%02X", value));
        }
        return allZero ? null : builder.toString();
    }

    /** 标签序列：0 结束，0xC0 开头的是压缩指针（占两字节）。 */
    private static int skipLabels(byte[] data, int pos, int length) {
        while (pos < length) {
            int label = data[pos] & 0xFF;
            if (label == 0) {
                return pos + 1;
            }
            if ((label & 0xC0) != 0) {
                return pos + 2;
            }
            pos += label + 1;
        }
        return length;
    }

    private static void writeHalfBytes(ByteArrayOutputStream out, int value) {
        out.write('A' + (value >> 4));
        out.write('A' + (value & 0x0F));
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value & 0xFF);
    }

    private static int read16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static final class Names {
        String workstation;
        String mac;
        boolean fileServer;
        boolean domainController;
        boolean browser;

        NameInfo toInfo() {
            return new NameInfo(workstation, mac, fileServer, domainController, browser);
        }
    }
}
