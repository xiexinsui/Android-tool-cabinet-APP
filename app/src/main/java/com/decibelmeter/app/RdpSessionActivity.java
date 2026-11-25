package com.decibelmeter.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.presentation.SessionResolution;
import com.freerdp.freerdpcore.services.LibFreeRDP;

/**
 * 远程桌面的入口页：拿到凭据后交给 FreeRDP 官方 Android 客户端的会话界面。
 *
 * 2.0 把画面、触摸/鼠标、滚轮、缩放与键盘都换成 FreeRDP 自己的实现
 * （{@link SessionActivity} + SessionInputManager/TouchPointerView），
 * 这个页面只做三件事：规范化地址、取凭据（缺了先弹框）、把参数拼成 FreeRDP 的
 * {@link BookmarkBase} 并启动会话界面。
 *
 * 连接簿（{@link RdpActivity}）、局域网页与域名页都只传 EXTRA_HOST/PORT/NAME，
 * 契约与 1.x 保持一致。
 */
public final class RdpSessionActivity extends Activity {

    public static final String EXTRA_HOST = "rdp_host";
    public static final String EXTRA_PORT = "rdp_port";
    public static final String EXTRA_NAME = "rdp_name";

    private CredentialStore credentials;
    private String host;
    private int port;
    private String name;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 不能在字段初始化里用 this：Activity 还没 attach，getApplicationContext() 是 null
        credentials = new CredentialStore(this);
        host = RdpConnection.normalizeHost(getIntent().getStringExtra(EXTRA_HOST));
        port = getIntent().getIntExtra(EXTRA_PORT, LanHost.PORT_RDP);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (host == null || host.isEmpty()) {
            finish();
            return;
        }

        CredentialStore.Credential saved = credentials.get(host);
        if (saved == null || saved.user.isEmpty()) {
            CredentialDialog.show(this, credentials, host,
                    getString(R.string.rdp_session_need_account,
                            name == null || name.isEmpty() ? host : name),
                    android.R.string.ok, this::launch);
            return;
        }
        launch(saved);
    }

    private void launch(CredentialStore.Credential credential) {
        if (!LibFreeRDP.isAvailable()) {
            Toast.makeText(this, R.string.rdp_native_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        RdpSettings settings = new RdpSettings(this);
        // 桌面尺寸由「分辨率」那一份设置决定（连接簿的显示设置和会话页工具栏改的是同一个值）。
        // 平板第一次用默认自适应；无论手机还是平板，勾了自适应都必须传 fitscreen 而不是 custom，
        // 否则 SessionActivity.onConfigurationChanged 里的 isFitScreen() 永远为假，旋转/分屏不会重谈尺寸。
        int[] requested = SessionResolution.load(this);
        int[] size = SessionResolution.resolve(this, requested);

        BookmarkBase bookmark = new BookmarkBase();
        bookmark.setType(BookmarkBase.TYPE_MANUAL);
        bookmark.setLabel(name == null || name.isEmpty() ? host : name);
        // hostname 稍后会被替换成实测可达的地址；域名仍留在 label 上
        bookmark.setHostname(host);
        bookmark.setPort(port);
        bookmark.setUsername(credential.user);
        bookmark.setDomain(credential.domain);
        bookmark.setPassword(credential.password);

        BookmarkBase.ScreenSettings screen = bookmark.getActiveScreenSettings();
        if (SessionResolution.isAdaptive(requested)) {
            screen.setResolution("fitscreen", size[0], size[1]);
        } else {
            screen.setResolution("custom", size[0], size[1]);
        }
        screen.setColors(settings.colorDepth());

        BookmarkBase.PerformanceFlags performance = bookmark.getPerformanceFlags();
        performance.setRemoteFX(settings.lossyCodecs());
        // GFX 与 FreeRDP 的 Android 客户端配合得最好；H.264 需要 openh264，本工程没编，
        // 关掉后服务端只发 RemoteFX/位图，和 1.x 的画质档位一致
        performance.setGfx(true);
        performance.setH264(false);

        BookmarkBase.AdvancedSettings advanced = bookmark.getAdvancedSettings();
        advanced.setRedirectSound(0); // 声音播放在手机上
        advanced.setRedirectMicrophone(false);
        advanced.setRedirectCamera(false);
        advanced.setRedirectSDCard(false);
        advanced.setRedirectPrinter(false);

        Intent intent = new Intent(this, SessionActivity.class);
        intent.putExtra(SessionActivity.PARAM_BOOKMARK, bookmark);
        // 面板里显示用户填的域名；实际连接用的 IP 另算
        intent.putExtra(SessionActivity.PARAM_HOST_NAME, host);

        final String hostForProbe = host;
        final int portForProbe = port;
        new Thread(() -> {
            Probe result = probe(hostForProbe, portForProbe);
            if (result.address != null) {
                // 用「实测可达的地址」连：DDNS 频繁换地址、或域名同时有 IPv6/IPv4 时，
                // FreeRDP 自己解析可能撞上旧记录或不可达的那一条（实测就撞过 No route to host）
                bookmark.setHostname(result.address);
                intent.putExtra(SessionActivity.PARAM_BOOKMARK, bookmark);
            }
            intent.putExtra(SessionActivity.PARAM_CONNECT_MS, result.millis);
            runOnUiThread(() -> {
                startActivity(intent);
                finish();
            });
        }, "rdp-probe").start();
    }

    private static final class Probe {
        /** 实测能建立 TCP 连接的地址（字面量）；null 表示全部失败，交给 FreeRDP 自己解析 */
        final String address;
        /** 三次握手耗时（毫秒），-1 表示没测到 */
        final long millis;

        Probe(String address, long millis) {
            this.address = address;
            this.millis = millis;
        }
    }

    /**
     * 解析域名并逐个尝试 TCP 连接（IPv6/IPv4 都会试），返回第一个连通的地址与耗时。
     * 全部失败时 address 为 null，让 FreeRDP 走自己的解析与报错。
     */
    private static Probe probe(String host, int port) {
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            int tried = 0;
            for (java.net.InetAddress address : addresses) {
                if (++tried > 4) {
                    break; // 双栈域名一般就两条，试太多只会让「连接中」拖得更久
                }
                long millis = tryConnect(address, port);
                if (millis >= 0) {
                    return new Probe(address.getHostAddress(), millis);
                }
            }
        } catch (Exception ignored) {
            // 解析失败：不阻断，交给 FreeRDP 报更具体的错
        }
        return new Probe(null, -1);
    }

    private static long tryConnect(java.net.InetAddress address, int port) {
        java.net.Socket socket = new java.net.Socket();
        try {
            long started = System.nanoTime();
            socket.connect(new java.net.InetSocketAddress(address, port), 1500);
            return (System.nanoTime() - started) / 1_000_000L;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
