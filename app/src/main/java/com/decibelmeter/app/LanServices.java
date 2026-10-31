package com.decibelmeter.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描的端口账本：每套预设探哪些端口、端口在界面上叫什么、一组开放端口拼起来像什么设备。
 *
 * 分「在场探测」和「补探」两级是因为耗时：在场端口要打到网段里每一个地址，254 × N 次连接，
 * 一次 250 ms 超时就摆在那里；补探只对已经应答过的地址再做，而一个网段里真正常开服务的
 * 通常只有几台。把 30 个端口平摊到整段上是把超时等待乘以 254，换不来多少新信息。
 *
 * 设备类型是猜的，端口、SSDP deviceType、mDNS 服务类型与型号只能说明「像什么」，界面上带
 * 「可能」语气，猜不出来就不显示，不做假确定的断言。
 */
final class LanServices {

    /** 一套预设：先探 presence，命中开放的设备再补探 extra。 */
    static final class Preset {
        final int labelRes;
        final int[] presence;
        final int[] extra;

        Preset(int labelRes, int[] presence, int[] extra) {
            this.labelRes = labelRes;
            this.presence = presence;
            this.extra = extra;
        }

        /** 一个空闲地址要付的探测次数，用来在界面上预估整轮耗时。 */
        int presenceCount() {
            return presence.length;
        }

        /** 整台设备最多探多少个端口。 */
        int maxPorts() {
            return presence.length + extra.length;
        }
    }

    /** 与旧版本行为一致：只关心共享文件与远程桌面这四个端口。 */
    static final Preset PRESET_CORE = new Preset(R.string.lan_preset_core,
            new int[]{LanHost.PORT_RPC, LanHost.PORT_NETBIOS, LanHost.PORT_SMB, LanHost.PORT_RDP},
            new int[0]);

    /** 加上进不出得去的常见入口：SSH、Web 管理页、HTTPS。 */
    static final Preset PRESET_COMMON = new Preset(R.string.lan_preset_common,
            new int[]{22, 80, 135, 139, 443, 445, 3389, 8080},
            new int[0]);

    /** 在场端口同上，但对已应答的设备再补一圈服务端口，认设备类型靠这一步。 */
    static final Preset PRESET_ALL = new Preset(R.string.lan_preset_all,
            new int[]{22, 80, 135, 139, 443, 445, 3389, 8080},
            new int[]{21, 23, 25, 53, 110, 143, 161, 389, 515, 548, 554, 631, 873, 993, 995,
                    1433, 1521, 1883, 1935, 2375, 3000, 3128, 3306, 5000, 5001, 5060, 5357,
                    5432, 5900, 62078, 8000, 8081, 8091, 8096, 8123, 8443, 8920, 9000, 9100,
                    9443, 10000, 32400, 50001});

    /** 单台设备详扫的低端口区间上界：1–1024 覆盖绝大多数约定端口。 */
    static final int DETAIL_FROM = 1;
    static final int DETAIL_TO = 1024;
    /**
     * 详扫再补一圈常见的高位服务端口，1024 以上没有区间可扫，只能点名。
     * 新增端口时这里、{@link #PRESET_ALL} 的 extra 与 {@link #acronym(int)} 要一起补，
     * 否则会出现「角色判断认这个端口、但没有任何预设去探」的死规则。
     */
    static final int[] DETAIL_HIGH = {1433, 1521, 1883, 1935, 2375, 3000, 3128, 3306, 3389,
            5000, 5001, 5060, 5432, 5900, 62078, 8000, 8080, 8081, 8091, 8096, 8123, 8443,
            8920, 9000, 9100, 9443, 10000, 27017, 32400, 50000, 50001};

    private static final Preset[] PRESETS = {PRESET_CORE, PRESET_COMMON, PRESET_ALL};
    /** 自定义端口集不是固定数组，单独占一个索引。 */
    static final int PRESET_CUSTOM_INDEX = PRESETS.length;

    private LanServices() {
    }

    static Preset customPreset(int[] ports) {
        return new Preset(R.string.lan_preset_custom, ports, new int[0]);
    }

    /**
     * 解析自定义端口：逗号、空格或顿号分隔都收，返回排序去重后的数组；空串或越界返回 null。
     * 端口数设上限，避免有人把 1–65535 全粘进来把一轮扫描拖成几分钟。
     */
    static int[] parsePorts(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split("[,，、\\s]+");
        java.util.TreeSet<Integer> ports = new java.util.TreeSet<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int port = Integer.parseInt(part);
                if (port < 1 || port > 65535) {
                    return null;
                }
                ports.add(port);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (ports.isEmpty() || ports.size() > 64) {
            return null;
        }
        int[] result = new int[ports.size()];
        int at = 0;
        for (int port : ports) {
            result[at++] = port;
        }
        return result;
    }

    static int presetCount() {
        return PRESETS.length;
    }

    /** 越界一律回落到第一套，配置里存的是索引，改了预设顺序也不该崩。 */
    static Preset presetAt(int index) {
        return index >= 0 && index < PRESETS.length ? PRESETS[index] : PRESETS[0];
    }

    static int presetIndexOf(Preset preset) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == preset) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 端口标签：四个决定「能不能用」的端口沿用本地化文案（用户要挑共享还是远程桌面），
     * 其余按 `端口/缩写` 显示——服务缩写是行业通名，不值得为它开三十条翻译。
     */
    static String label(Context context, int port) {
        switch (port) {
            case LanHost.PORT_RPC:
                return context.getString(R.string.lan_port_rpc);
            case LanHost.PORT_NETBIOS:
                return context.getString(R.string.lan_port_netbios);
            case LanHost.PORT_SMB:
                return context.getString(R.string.lan_port_smb);
            case LanHost.PORT_RDP:
                return context.getString(R.string.lan_port_rdp);
            default:
                break;
        }
        String shortName = acronym(port);
        // 详扫常撞到没登记过的端口（实测路由器上的 784），这时只显示数字，不要留一个光秃秃的斜杠
        return shortName.isEmpty() ? String.valueOf(port) : port + "/" + shortName;
    }

    /** 详扫可能撞见没在服务表里的端口，退回纯数字而不是空字符串。 */
    private static String acronym(int port) {
        switch (port) {
            case 20: return "FTP-DATA";
            case 21: return "FTP";
            case 22: return "SSH";
            case 23: return "TELNET";
            case 25: return "SMTP";
            case 53: return "DNS";
            case 80: return "HTTP";
            case 110: return "POP3";
            case 137: return "NBNS";
            case 143: return "IMAP";
            case 161: return "SNMP";
            case 389: return "LDAP";
            case 443: return "HTTPS";
            case 445: return "SMB";
            case 512: case 513: case 514: return "R-*";
            case 515: return "LPR";
            case 548: return "AFP";
            case 554: return "RTSP";
            case 631: return "IPP";
            case 873: return "RSYNC";
            case 993: return "IMAPS";
            case 995: return "POPS";
            case 1433: return "MSSQL";
            case 1521: return "ORACLE";
            case 1883: return "MQTT";
            case 1935: return "RTMP";
            case 2375: return "DOCKER";
            case 3000: return "WEB";
            case 3128: return "PROXY";
            case 3306: return "MySQL";
            case 3389: return "RDP";
            case 5000: return "UPnP";
            case 5001: return "SYNO";
            case 5060: case 5061: return "SIP";
            case 5357: return "WSD";
            case 5432: return "PG";
            case 5900: return "VNC";
            case 62078: return "IOS";
            case 8000: case 8080: case 32400: return "HTTP";
            case 8081: return "HTTP";
            case 8443: case 9443: return "HTTPS";
            case 8091: return "EMBY";
            case 8096: return "JELLY";
            case 8123: return "HOME";
            case 8920: return "JELLY";
            case 9000: return "SONAR";
            case 9100: return "PJL";
            case 10000: return "NAS";
            case 27017: return "MONGO";
            case 50001: return "SYNO";
            default: return "";
        }
    }

    /** 网页入口的端口顺序：HTTPS 优先，其次按常见程度排列。 */
    private static final int[] WEB_HTTPS = {443, 8443, 8081, 5001, 8920, 9443};
    private static final int[] WEB_HTTP = {80, 8080, 3000, 5000, 8000, 8096, 8091, 8123,
            9000, 10000, 32400};

    static boolean isHttpsPort(int port) {
        for (int candidate : WEB_HTTPS) {
            if (candidate == port) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一台设备可能同时开着 80/443/8080，全部列出来，别让菜单只给第一个。
     * 路由器、NAS、打印机普遍在这些端口上放管理页面。
     */
    static List<Integer> webPorts(Set<Integer> ports) {
        List<Integer> found = new ArrayList<>();
        for (int port : WEB_HTTPS) {
            if (ports.contains(port)) {
                found.add(port);
            }
        }
        for (int port : WEB_HTTP) {
            if (ports.contains(port)) {
                found.add(port);
            }
        }
        return found;
    }

    static int webPort(Set<Integer> ports) {
        List<Integer> all = webPorts(ports);
        return all.isEmpty() ? -1 : all.get(0);
    }

    static String webUrl(String host, int port) {
        boolean https = isHttpsPort(port);
        StringBuilder url = new StringBuilder(https ? "https://" : "http://").append(host);
        if (!((https && port == 443) || (!https && port == 80))) {
            url.append(':').append(port);
        }
        url.append('/');
        return url.toString();
    }

    /**
     * 设备类型线索。域控与主浏览器这两种身份由 NetBIOS 角色后缀直接给出，比端口确定得多，
     * 所以先判它们；其次是 mDNS 服务类型（`_airplay._tcp.` 就是苹果那套），最后才按端口组合猜。
     * 返回 0 表示猜不出来，界面这一行直接不显示。
     */
    static int roleRes(LanHost host) {
        if (host.gateway) {
            return R.string.lan_role_gateway;
        }
        if (host.nbnsDomainController) {
            return R.string.lan_role_dc;
        }
        if (host.nbnsBrowser) {
            return R.string.lan_role_browser;
        }
        Set<Integer> ports = host.openPorts();
        String ssdp = host.ssdpType == null ? null : host.ssdpType.toLowerCase(Locale.US);
        if (ssdp != null) {
            if (ssdp.contains("internetgatewaydevice")) {
                return R.string.lan_role_gateway;
            }
            if (ssdp.contains("mediarenderer") || ssdp.contains("mediaserver")) {
                return R.string.lan_role_media;
            }
            if (ssdp.contains("printer")) {
                return R.string.lan_role_printer;
            }
        }
        String model = host.model == null ? null : host.model.toLowerCase(Locale.US);
        if (model != null) {
            if (model.contains("diskstation") || model.contains("synology")) {
                return R.string.lan_role_nas;
            }
            if (model.contains("macbook") || model.contains("imac")) {
                return R.string.lan_role_mac;
            }
        }
        String mdnsType = host.mdnsType;
        if (mdnsType != null) {
            String type = mdnsType.toLowerCase(Locale.US);
            if (type.contains("airplay") || type.contains("tvremoteservice")) {
                return R.string.lan_role_apple_tv;
            }
            if (type.contains("raop")) {
                return R.string.lan_role_airplay_audio;
            }
            if (type.contains("googlecast")) {
                return R.string.lan_role_chromecast;
            }
            if (type.contains("uscan")) {
                return R.string.lan_role_scanner;
            }
            if (type.contains("printer") || type.contains("_ipp") || type.contains("pdl")) {
                return R.string.lan_role_printer;
            }
            if (type.contains("afpovertcp")) {
                return R.string.lan_role_mac;
            }
            if (type.contains("nas") || type.contains("diskstation")) {
                return R.string.lan_role_nas;
            }
        }
        boolean smb = ports.contains(LanHost.PORT_SMB) || ports.contains(LanHost.PORT_NETBIOS);
        boolean rdp = ports.contains(LanHost.PORT_RDP);
        if (rdp && smb) {
            return R.string.lan_role_windows_pc;
        }
        if (ports.contains(631) || ports.contains(9100) || ports.contains(515)
                || ports.contains(5357)) {
            return R.string.lan_role_printer;
        }
        if (ports.contains(554) || ports.contains(1935)) {
            return R.string.lan_role_camera;
        }
        if (ports.contains(32400) || ports.contains(8096) || ports.contains(8091)
                || ports.contains(5001)) {
            return R.string.lan_role_media;
        }
        if (smb) {
            return rdp ? R.string.lan_role_windows_pc : R.string.lan_role_share;
        }
        if (ports.contains(1433) || ports.contains(3306) || ports.contains(5432)
                || ports.contains(27017) || ports.contains(1883)) {
            return R.string.lan_role_server;
        }
        if (LanServices.webPort(ports) > 0) {
            return R.string.lan_role_webdevice;
        }
        if (ports.contains(22)) {
            return R.string.lan_role_linux;
        }
        return 0;
    }

    /** 详扫的完整端口清单：1–1024 连续段，加上 1024 以上点名的常见服务端口。 */
    static int[] detailPorts() {
        int high = 0;
        for (int port : DETAIL_HIGH) {
            if (port > DETAIL_TO) {
                high++;
            }
        }
        int[] ports = new int[(DETAIL_TO - DETAIL_FROM + 1) + high];
        int at = 0;
        for (int port = DETAIL_FROM; port <= DETAIL_TO; port++) {
            ports[at++] = port;
        }
        for (int port : DETAIL_HIGH) {
            if (port > DETAIL_TO) {
                ports[at++] = port;
            }
        }
        return ports;
    }
}
