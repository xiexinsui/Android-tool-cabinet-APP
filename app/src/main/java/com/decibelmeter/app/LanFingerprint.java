package com.decibelmeter.app;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 服务指纹：端口组合只能说明「像什么」，真正能回答「这是什么」的是管理页标题、
 * Server 头、SSH banner、RTSP 应答和 RDP 安全层。
 *
 * 只在用户主动点「识别设备」时跑，不参与每轮扫描：这里会向设备发 HTTP GET，
 * 是明确的主动行为，不该在后台悄悄发生。
 */
final class LanFingerprint {

    static final class Result {
        String summary;
        String detail;
    }

    private static final int HTTP_TIMEOUT_MS = 1200;
    private static final int BANNER_TIMEOUT_MS = 1000;
    private static final int MAX_BODY = 64 * 1024;

    private LanFingerprint() {
    }

    static Result identify(Context context, String ip, Set<Integer> ports) {
        Result result = new Result();
        List<String> lines = new ArrayList<>();

        List<Integer> webPorts = LanServices.webPorts(ports);
        int tried = 0;
        for (int port : webPorts) {
            if (tried++ >= 2) {
                break;
            }
            LanHttp.Response response = LanHttp.get(ip, port,
                    LanServices.isHttpsPort(port), "/", HTTP_TIMEOUT_MS, MAX_BODY);
            if (response.status > 0) {
                String server = response.header("server");
                lines.add("HTTP :" + port + " → " + response.status
                        + (server == null ? "" : " · " + LanHttp.truncate(server, 80)));
                String title = LanHttp.title(response.body);
                if (title != null) {
                    lines.add(context.getString(R.string.lan_fp_title, title));
                    if (result.summary == null) {
                        result.summary = title;
                    }
                } else if (server != null && result.summary == null) {
                    result.summary = LanHttp.truncate(server, 60);
                }
                String location = response.header("location");
                if (location != null) {
                    lines.add(context.getString(R.string.lan_fp_location,
                            LanHttp.truncate(location, 120)));
                }
                if (response.tlsSubject != null) {
                    lines.add(context.getString(R.string.lan_fp_certificate,
                            LanHttp.truncate(response.tlsSubject, 120)));
                }
                if (title != null) {
                    break;
                }
            } else if (response.error != null) {
                lines.add("HTTP :" + port + " → " + response.error);
            }
        }

        if (ports.contains(22)) {
            String banner = sshBanner(ip);
            if (banner != null) {
                lines.add(context.getString(R.string.lan_fp_ssh, banner));
                if (result.summary == null) {
                    result.summary = "SSH · " + LanHttp.truncate(banner, 50);
                }
            }
        }

        if (ports.contains(554)) {
            String server = rtspServer(ip);
            if (server != null) {
                lines.add(context.getString(R.string.lan_fp_rtsp, server));
                if (result.summary == null) {
                    result.summary = "RTSP · " + LanHttp.truncate(server, 50);
                }
            }
        }

        if (ports.contains(LanHost.PORT_RDP)) {
            RdpProbe.Result probe = RdpProbe.run(ip, LanHost.PORT_RDP, 1500);
            if (probe.responded) {
                String security = context.getString(probe.requiresNla()
                        ? R.string.lan_fp_nla : R.string.lan_fp_tls);
                lines.add(context.getString(R.string.lan_fp_rdp, security, probe.rttMillis));
                if (result.summary == null) {
                    result.summary = "RDP · " + security;
                }
            }
        }

        if (lines.isEmpty()) {
            return null;
        }
        result.detail = String.join("\n", lines);
        if (result.summary == null) {
            result.summary = LanHttp.truncate(lines.get(0), 60);
        }
        return result;
    }

    private static String sshBanner(String ip) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, 22), BANNER_TIMEOUT_MS);
            socket.setSoTimeout(BANNER_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            int length = 0;
            while (length < buffer.length) {
                int read = in.read(buffer, length, buffer.length - length);
                if (read < 0) {
                    break;
                }
                length += read;
                if (buffer[length - 1] == '\n') {
                    break;
                }
            }
            String line = new String(buffer, 0, length, StandardCharsets.US_ASCII).trim();
            return line.startsWith("SSH-") ? LanHttp.truncate(line, 90) : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // 识别用的临时连接
            }
        }
    }

    private static String rtspServer(String ip) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, 554), BANNER_TIMEOUT_MS);
            socket.setSoTimeout(BANNER_TIMEOUT_MS);
            String request = "OPTIONS rtsp://" + ip + ":554/ RTSP/1.0\r\n"
                    + "CSeq: 1\r\nUser-Agent: ToolKit-Lan/1.0\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] buffer = new byte[4096];
            int length = socket.getInputStream().read(buffer);
            if (length <= 0) {
                return null;
            }
            String text = new String(buffer, 0, length, StandardCharsets.ISO_8859_1);
            for (String line : text.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("server")) {
                    return LanHttp.truncate(line.substring(colon + 1).trim(), 90);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // 识别用的临时连接
            }
        }
    }
}
