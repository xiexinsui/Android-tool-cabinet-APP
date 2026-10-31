package com.decibelmeter.app;

import java.nio.charset.StandardCharsets;

/**
 * 内置远程桌面的原生层封装（librdpcore.so，源码在工程根目录的 rdpcore/）。
 *
 * 所有跨边界的文本都以 UTF-8 字节数组传，Java 侧再解码：原生侧刻意不用
 * {@code NewStringUTF}，那个函数要的是「修改版 UTF-8」，中文机器名或账号会出乱码。
 *
 * 库加载失败时这里不抛异常，只让 {@link #isAvailable()} 返回 false——一台跑不了这个 ABI 的
 * 老设备或者没打进 .so 的构建，应该收起「内置连接」入口，而不是让局域网页整个打不开。
 */
public final class RdpNative {

    /** 与 rdpcore/src/lib.rs 里的 STATE_* 一一对应。 */
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_FAILED = 3;
    public static final int STATE_CLOSED = 4;

    private static final boolean LOADED = load();

    private static boolean load() {
        try {
            System.loadLibrary("rdpcore");
            return true;
        } catch (Throwable t) {
            // UnsatisfiedLinkError 之外还可能是 SecurityException（个别定制系统限制加载路径）
            return false;
        }
    }

    private RdpNative() {
    }

    public static boolean isAvailable() {
        return LOADED && nativeAvailable();
    }

    public static String version() {
        return LOADED ? decode(nativeVersion()) : "";
    }

    public static String error() {
        return LOADED ? decode(nativeError()) : "";
    }

    public static String inputStats() {
        return LOADED ? decode(nativeInputStats()) : "";
    }

    /**
     * 起会话。账号、域、密码由调用方在同一进程里从 {@link CredentialStore} 取好后传进来，
     * 只经这一条 JNI 边界、留在原生内存里：不走 Intent、不进连接簿文件、不写日志。
     * {@code device} 是送进协议的本机名（用 Build.MODEL），空着由原生侧给默认值。
     * {@code width}/{@code height} 是要向服务端请求的桌面尺寸，给 0 用原生侧默认值；
     * 原生侧会按协议约束（宽必须为偶数、200–8192）收敛。
     */
    public static int start(String host, int port, String user, String domain, String password,
                            String device, int width, int height, int colorDepth, boolean lossy,
                            android.content.Context context) {
        return LOADED
                ? nativeStart(host, port, user, domain, password, device, width, height,
                        colorDepth, lossy, context)
                : -100;
    }

    public static int state() {
        return LOADED ? nativeState() : STATE_FAILED;
    }

    public static int width() {
        return LOADED ? nativeWidth() : 0;
    }

    public static int height() {
        return LOADED ? nativeHeight() : 0;
    }

    /**
     * 取一帧。传回当前持有的帧号，返回新帧号并写入 {@code pixels}；没有新帧返回 0。
     * 帧号既当版本号也当参数，避免每 33 ms 白拷一次 3 MB。
     */
    public static int copyFrame(int serial, int[] pixels) {
        return LOADED ? nativeCopyFrame(serial, pixels) : 0;
    }

    /**
     * 指针事件。{@code button}：0=纯移动、1=左、2=右、3=中、8/9=侧键；
     * {@code down} 区分按下与抬起。
     * 抬手必须以 {@code down=false} 发同一条按钮事件，只发"移动"远端会认为按键还按着。
     */
    public static void pointer(int x, int y, int button, boolean down) {
        if (LOADED) {
            nativePointer(x, y, button, down);
        }
    }

    /** 累计收到的帧数。配合 {@link #frameBytes()} 让界面算出真实帧率与流量。 */
    public static long frameCount() {
        return LOADED ? nativeFrameCount() : 0L;
    }

    /** 累计收到的字节数（按 宽×高×4 估的像素载荷）。 */
    public static long frameBytes() {
        return LOADED ? nativeFrameBytes() : 0L;
    }

    /**
     * 滚轮：{@code amount} 为格数（Windows 一格 120）。
     * {@code vertical=true} 为纵向（正数向上），false 为横向（正数向右）。
     */
    public static void wheel(int x, int y, int amount, boolean vertical) {
        if (LOADED) {
            nativeWheel(x, y, amount, vertical);
        }
    }

    /**
     * 远端光标形状。{@code meta} = [width, height, hotspotX, hotspotY, kind]，
     * kind：0=用位图、1=服务端要求隐藏、2=用客户端默认箭头。
     * 传回已持有的序号，返回新序号；没有变化返回 0（逐帧调用只比较一个整数）。
     */
    public static int pointerInfo(int knownSerial, int[] meta) {
        return LOADED ? nativePointerInfo(knownSerial, meta) : 0;
    }

    /** 取光标位图（ARGB，非预乘）。返回写进去的像素个数，0 表示没有可用位图。 */
    public static int pointerPixels(int[] out) {
        return LOADED ? nativePointerPixels(out) : 0;
    }

    /**
     * 扫描码按键：功能键、方向键、Win 这类要真实键位的走这条。
     * 键位用 u16 形式，0xE000 位表示扩展键（方向键与 Insert/Home/PageUp 一族都在扩展区）。
     */
    public static void scancode(int scancode, boolean down) {
        if (LOADED) {
            nativeScancode(scancode, down);
        }
    }

    /** Unicode 按键：软键盘输入的字符走这条，远端不必知道手机是什么键盘布局。 */
    public static void unicode(int codePoint, boolean down) {
        if (LOADED) {
            nativeUnicode(codePoint, down);
        }
    }

    /**
     * 松开所有还按着的键与按钮。
     * 切后台、弹输入法、换指针模式前都要调：不释放的话远端会一直以为 Ctrl 还按着，
     * 回来就是"什么都没点但全在选中文本"。
     */
    public static void releaseAll() {
        if (LOADED) {
            nativeReleaseAll();
        }
    }

    /** 这次会话的声音通道有没有打开（RDPSND 协商成功）。 */
    public static boolean audioEnabled() {
        return LOADED && nativeAudioEnabled();
    }

    /** 预检 TCP 握手的往返耗时（毫秒），0 = 没测到。 */
    public static long connectMillis() {
        return LOADED ? nativeConnectMs() : 0L;
    }

    /** 累计收到的音频包数：排查"没声音"时看它涨不涨。 */
    public static long audioPackets() {
        return LOADED ? nativeAudioPackets() : 0L;
    }

    public static void stop() {
        if (LOADED) {
            nativeStop();
        }
    }

    private static String decode(byte[] raw) {
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    private static native boolean nativeAvailable();

    private static native byte[] nativeVersion();

    private static native byte[] nativeError();

    private static native byte[] nativeInputStats();

    /** {@code context} 是 Application Context：cpal 的 Android 后端要拿它查音频参数。 */
    private static native int nativeStart(String host, int port, String user, String domain,
                                          String password, String device, int width, int height,
                                          int colorDepth, boolean lossy, android.content.Context context);

    private static native int nativeState();

    private static native int nativeWidth();

    private static native int nativeHeight();

    private static native int nativeCopyFrame(int serial, int[] pixels);

    private static native void nativePointer(int x, int y, int button, boolean down);

    private static native long nativeFrameCount();

    private static native long nativeFrameBytes();

    private static native void nativeWheel(int x, int y, int amount, boolean vertical);

    private static native int nativePointerInfo(int knownSerial, int[] meta);

    private static native int nativePointerPixels(int[] out);

    private static native void nativeScancode(int scancode, boolean down);

    private static native void nativeUnicode(int codePoint, boolean down);

    private static native void nativeReleaseAll();

    private static native void nativeStop();

    private static native boolean nativeAudioEnabled();

    private static native long nativeAudioPackets();

    private static native long nativeConnectMs();
}
