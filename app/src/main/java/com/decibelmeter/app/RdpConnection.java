package com.decibelmeter.app;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一条远程桌面连接：显示名、地址、端口。
 *
 * 账号与密码不在这里。凭据统一由 {@link CredentialStore} 按主机标识加密保存，连接簿只记
 * 「连哪儿」——同一份密码在两个地方落盘，就多一处会被翻出来的地方。主机标识直接复用 host
 * 字段，所以扫描页里给这台机器存过的账号，连接簿里连它时会原样带上。
 */
public final class RdpConnection {

    public final String name;
    public final String host;
    public final int port;

    public RdpConnection(String name, String host, int port) {
        this.name = name == null ? "" : name.trim();
        this.host = normalizeHost(host);
        this.port = port > 0 && port < 65536 ? port : LanHost.PORT_RDP;
    }

    /**
     * 地址统一存"裸"形式：IPv6 的方括号在这里去掉。
     *
     * 存裸地址是为了同一台机器只有一种写法——凭据按主机字符串存，[fe80::1] 与 fe80::1
     * 各存一份就永远对不上；显示和拼 URI 时再按需要补回方括号。
     */
    public static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.length() > 1 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** IPv6 字面量：地址里一定带冒号（主机名不会）。 */
    public static boolean isIpv6(String host) {
        return host != null && host.indexOf(':') >= 0;
    }

    /** 从扫描结果建一条连接：名字用设备名，地址用 IP，端口用标准的 3389。 */
    public static RdpConnection fromHost(LanHost host) {
        return new RdpConnection(host.displayName(), host.ip, LanHost.PORT_RDP);
    }

    /** 标准端口就不显示尾巴，界面上一眼能看出这台是换了端口的。 */
    public String address() {
        String shown = isIpv6(host) ? "[" + host + "]" : host;
        return port == LanHost.PORT_RDP ? shown : shown + ":" + port;
    }

    public String displayLabel() {
        return name.isEmpty() ? address() : name;
    }

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * 是不是字面量地址（IPv4 或 IPv6）。
     *
     * 主机名要查一次 DNS，所以连接前得先分辨：字面量直接连，域名先解析。IPv6 里一定有冒号，
     * 拿它做判别比写一套完整文法可靠，也不会把 "nas.lan" 误判成地址。
     */
    public static boolean isLiteralIp(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        return host.indexOf(':') >= 0 || IPV4.matcher(host).matches();
    }

    /** 同一台机器的同一端口算同一条连接，重复添加时提示而不是攒一堆几乎一样的条目。 */
    public boolean sameTarget(RdpConnection other) {
        return other != null && port == other.port
                && host.toLowerCase(Locale.US).equals(other.host.toLowerCase(Locale.US));
    }
}
