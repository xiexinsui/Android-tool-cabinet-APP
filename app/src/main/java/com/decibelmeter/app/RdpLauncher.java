package com.decibelmeter.app;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * 远程桌面的导出与探测：连接本身走内置客户端（{@link RdpSessionActivity}），这里只负责
 * 把「连哪台、用什么账号」变成可带走的两种形式——
 * <ul>
 *   <li>{@code freerdp://} 链接：复制出去，粘到别的客户端里能用；</li>
 *   <li>{@code .rdp} 文件：RDP 6 的 INI 式文本格式，拿到电脑上可以双击用 mstsc 连。</li>
 * </ul>
 *
 * 两种都不带密码。URI 会进对端进程的命令行、也可能落进日志；{@code .rdp} 的
 * {@code password 51:b:} 字段只是可逆编码，等于明文。密码留在 {@link CredentialStore} 里，
 * 连接时由客户端自己问一次。
 */
public final class RdpLauncher {

    private static final int PROBE_TIMEOUT_MS = 1500;

    private RdpLauncher() {
    }

    /** 组装 freerdp:// 链接：用户名可能形如「域\用户」，交给 Uri.encode 转义，对端会解回去。 */
    public static Uri buildUri(RdpConnection connection, CredentialStore.Credential credential) {
        StringBuilder authority = new StringBuilder();
        if (credential != null && !credential.user.isEmpty()) {
            authority.append(Uri.encode(credential.user)).append('@');
        }
        // IPv6 在 URI 的 authority 里必须带方括号
        authority.append(RdpConnection.isIpv6(connection.host)
                        ? "[" + connection.host + "]" : connection.host)
                .append(':').append(connection.port);
        Uri.Builder builder = new Uri.Builder()
                .scheme("freerdp")
                .encodedAuthority(authority.toString())
                .appendPath("connect");
        if (credential != null && !credential.domain.isEmpty()) {
            builder.appendQueryParameter("d", credential.domain);
        }
        return builder.build();
    }

    /**
     * 端口连通性测试：TCP 三次握手能成就连通，返回往返耗时；连不上返回 -1。
     *
     * 这是「handoff 之前先确认对端真的应答」的最小办法——RDP 协议本身要 TLS/CredSSP 一大套，
     * 握手成不成只能说明端口通，不能保证一定能登录进去，界面上的措辞按这个来。
     */
    public static long probe(String host, int port) {
        Socket socket = new Socket();
        long started = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port),
                    PROBE_TIMEOUT_MS);
            return System.currentTimeMillis() - started;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // 探测用的连接，关不掉也没有后果
            }
        }
    }

    /**
     * 写出一个 .rdp 文件，返回它的位置。
     *
     * 格式是 RDP 6 的「键:类型:值」逐行文本。用 UTF-8 写：地址与账号基本都在 ASCII 里，
     * 与 mstsc 习惯读的 ANSI 字节一致，也不会像 UTF-16 BOM 那样把只会按行读文本的解析器绊倒。
     *
     * 分辨率按 1280×800 给：手机横屏接不上，但导出的文件多半在电脑上打开，留个能用桌面比例
     * 起步的默认值比塞一个竖屏尺寸合理。
     */
    public static File writeRdpFile(Context context, RdpConnection connection,
                                   CredentialStore.Credential credential) throws IOException {
        File dir = new File(context.getFilesDir(), "rdp");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建导出目录");
        }
        File out = new File(dir, FileUtil.sanitize(connection.displayLabel()) + ".rdp");
        StringBuilder text = new StringBuilder();
        // .rdp 的 full address 同样要求 IPv6 带方括号
        String shown = RdpConnection.isIpv6(connection.host)
                ? "[" + connection.host + "]" : connection.host;
        String address = connection.port == LanHost.PORT_RDP
                ? shown : shown + ":" + connection.port;
        line(text, "full address", "s", address);
        if (connection.port != LanHost.PORT_RDP) {
            line(text, "port", "i", String.valueOf(connection.port));
        }
        if (credential != null && !credential.user.isEmpty()) {
            line(text, "username", "s", credential.user);
        }
        if (credential != null && !credential.domain.isEmpty()) {
            line(text, "domain", "s", credential.domain);
        }
        line(text, "screen mode id", "i", "2");
        line(text, "desktopwidth", "i", "1280");
        line(text, "desktopheight", "i", "800");
        line(text, "session bpp", "i", "24");
        line(text, "connection type", "i", "7");
        line(text, "redirectclipboard", "i", "1");
        // 明确让客户端在连的时候问一次密码：文件本身不带凭据
        line(text, "prompt for credentials", "i", "1");
        line(text, "authenticate server", "i", "0");

        Writer writer = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            writer.write(text.toString());
        } finally {
            writer.close();
        }
        return out;
    }

    private static void line(StringBuilder out, String key, String type, String value) {
        out.append(key).append(':').append(type).append(':').append(value).append("\r\n");
    }

    /**
     * 写出 .rdp 并交给系统分享。
     *
     * 写文件与查授权都在后台线程，回到主线程才弹分享面板，所以扫描页与连接簿可以用同一个
     * 入口，不必各自再抄一份线程切换。
     */
    public static void shareRdpFile(final Activity activity, final RdpConnection connection,
                                    final CredentialStore.Credential credential) {
        new Thread(() -> {
            File file;
            try {
                file = writeRdpFile(activity, connection, credential);
            } catch (Exception e) {
                final String reason = describe(e);
                activity.runOnUiThread(() -> toast(activity,
                        activity.getString(R.string.rdp_export_failed, reason)));
                return;
            }
            final File exported = file;
            activity.runOnUiThread(() -> {
                int code = FileShare.sendTo(activity, exported);
                toast(activity, code == FileShare.NO_APP
                        ? activity.getString(R.string.share_no_app, exported.getAbsolutePath())
                        : activity.getString(R.string.rdp_exported_format,
                                exported.getAbsolutePath()));
            });
        }).start();
    }

    private static void toast(Context context, String message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    /** 主机名与地址在界面上都按小写比较，避免同一个 NAS 被存成两条。 */
    static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.US);
    }
}
