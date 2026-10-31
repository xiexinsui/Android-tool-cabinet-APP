package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * 内置远程桌面的显示与操作偏好：指针模式、桌面尺寸、画面缩放方式、鼠标模式灵敏度。
 *
 * 这些是「跨会话都要一样」的设置，所以落 SharedPreferences，而不是只活在会话页里——
 * 每次重连都要重新选一遍分辨率，比远端桌面本身还让人烦。
 *
 * 桌面尺寸存的是**要向服务端请求**的值，与服务端实际给出的值可能不同：display-control
 * 会按协议约束把宽收敛成偶数，服务端也可能只接受自己的几个模式。界面上区分这两者，
 * 状态行显示的是实际尺寸。
 */
final class RdpSettings {

    /** 触控模式：手指点哪儿，远端光标就去哪儿。 */
    static final int MODE_TOUCH = 0;
    /** 鼠标模式：画面当触摸板，手指相对移动推动远端光标。 */
    static final int MODE_MOUSE = 1;

    /** 32 位色：颜色最准，代价是位图载荷翻倍。 */
    static final int DEPTH_CLEAR = 32;
    /** 16 位色：位图载荷砍一半，公网带宽紧张时最有效的一档。 */
    static final int DEPTH_FAST = 16;

    /**
     * 码率档位。三档都只影响"服务端发多少字节"，不改本地缩放：
     * <ul>
     *   <li><b>省流</b>：16 位色，接受 RemoteFX 有损编码；</li>
     *   <li><b>均衡</b>：32 位色，同上；</li>
     *   <li><b>清晰</b>：32 位色，关掉有损编码——文字不糊，位图载荷最大。</li>
     * </ul>
     */
    static final int PRESET_SAVE = 0;
    static final int PRESET_BALANCED = 1;
    static final int PRESET_SHARP = 2;

    /** 等比缩放居中，四周留黑边。 */
    static final int SCALE_FIT = 0;
    /** 拉满整个画面：手机竖屏接 16:9 桌面会把字拉扁，但确实能多看见一圈内容。 */
    static final int SCALE_STRETCH = 1;

    private static final String PREFS = "rdp_display";
    private static final String KEY_MODE = "mode";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_COLOR_DEPTH = "color_depth";
    private static final String KEY_QUALITY = "quality_preset";
    private static final String KEY_ADAPTIVE = "adaptive_size";

    private final SharedPreferences prefs;

    RdpSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    int pointerMode() {
        return prefs.getInt(KEY_MODE, MODE_TOUCH);
    }

    void savePointerMode(int mode) {
        prefs.edit().putInt(KEY_MODE, mode).apply();
    }

    int scaleMode() {
        return prefs.getInt(KEY_SCALE, SCALE_FIT);
    }

    void saveScaleMode(int mode) {
        prefs.edit().putInt(KEY_SCALE, mode).apply();
    }

    /** 码率档位：省流 / 均衡 / 清晰。上一版只有色深，16 位按省流迁移。 */
    int qualityPreset() {
        if (prefs.contains(KEY_QUALITY)) {
            return clamp(prefs.getInt(KEY_QUALITY, PRESET_BALANCED), PRESET_SAVE, PRESET_SHARP);
        }
        return prefs.getInt(KEY_COLOR_DEPTH, DEPTH_CLEAR) == DEPTH_FAST
                ? PRESET_SAVE : PRESET_BALANCED;
    }

    void saveQualityPreset(int preset) {
        prefs.edit().putInt(KEY_QUALITY, clamp(preset, PRESET_SAVE, PRESET_SHARP)).apply();
    }

    /** 连接参数里的色深，由码率档位决定。 */
    int colorDepth() {
        return qualityPreset() == PRESET_SAVE ? DEPTH_FAST : DEPTH_CLEAR;
    }

    /** 省流/均衡接受 RemoteFX 有损编码；清晰档关掉它，换文字锐利，代价是载荷变大。 */
    boolean lossyCodecs() {
        return qualityPreset() != PRESET_SHARP;
    }

    /** 自适应手机当前分辨率：远端桌面按手机此刻的实际像素来，1:1 不缩放。 */
    boolean adaptiveSize() {
        return prefs.getBoolean(KEY_ADAPTIVE, false);
    }

    void saveAdaptiveSize(boolean adaptive) {
        prefs.edit().putBoolean(KEY_ADAPTIVE, adaptive).apply();
    }

    /** 当前方向的实际屏幕像素（竖屏 1080×2340、横屏 2340×1080）。宽取偶数。 */
    static int[] adaptiveSize(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{even(metrics.widthPixels), even(metrics.heightPixels)};
    }

    /** 鼠标模式的灵敏度，百分数：100 表示手指移动多少像素，远端光标就走多少像素。 */
    int sensitivity() {
        return prefs.getInt(KEY_SENSITIVITY, 150);
    }

    void saveSensitivity(int percent) {
        prefs.edit().putInt(KEY_SENSITIVITY, clamp(percent, 50, 400)).apply();
    }

    int width() {
        return prefs.getInt(KEY_WIDTH, 0);
    }

    int height() {
        return prefs.getInt(KEY_HEIGHT, 0);
    }

    void saveSize(int width, int height) {
        prefs.edit().putInt(KEY_WIDTH, width).putInt(KEY_HEIGHT, height).apply();
    }

    /**
     * 「匹配本机」：按手机物理像素要一个横屏桌面。
     *
     * 这样远端一个像素正好落在屏幕一个像素上，字最锐利；代价是手机竖屏下画面会很小，
     * 所以它是个可选项而不是默认值。取整到偶数是因为协议要求宽度为偶数。
     */
    static int[] deviceSize(Context context) {
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        // 按 2x dpi 的手机算，横屏满像素要出来 2340×1080 这种细长桌面，远端图标会小到
        // 看不清；这里按竖屏宽度做限幅，保证短边至少留够 700 的可用高度
        int width = even(Math.min(longSide, 1920));
        int height = clamp(even((int) ((double) width * shortSide / longSide)), 600, 1200);
        return new int[]{width, height};
    }

    /** 默认桌面尺寸：绝大多数 Windows 桌面就是这个，字与图标大小最自然。 */
    static final int DEFAULT_WIDTH = 1920;
    static final int DEFAULT_HEIGHT = 1080;

    /** 预设列表：「匹配本机」用 0/0 表示按设备算。 */
    static final int[][] PRESETS = {
            {1920, 1080},
            {1600, 900},
            {1366, 768},
            {1280, 800},
            {1024, 768},
            // 「匹配本机」放最后：默认用 1920×1080，这是绝大多数 Windows 桌面的原生尺寸
            {0, 0},
    };

    static String label(Context context, int[] preset) {
        if (preset[0] == 0) {
            int[] size = deviceSize(context);
            return context.getString(R.string.rdp_size_match_phone_format, size[0], size[1]);
        }
        return preset[0] + "×" + preset[1];
    }

    /** 当前设置对应的预设下标；自定义尺寸（动态调整得到的）谁都不匹配，返回 -1。 */
    int presetIndex(Context context) {
        int[] size = effectiveSize(context);
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0] == 0) {
                int[] device = deviceSize(context);
                if (device[0] == size[0] && device[1] == size[1]) {
                    return i;
                }
                continue;
            }
            if (PRESETS[i][0] == size[0] && PRESETS[i][1] == size[1]) {
                return i;
            }
        }
        return -1;
    }

    /** 实际要请求的尺寸：没设置过就用「匹配本机」的横屏默认值。 */
    int[] effectiveSize(Context context) {
        if (adaptiveSize()) {
            return adaptiveSize(context);
        }
        int width = width();
        int height = height();
        if (width > 0 && height > 0) {
            return new int[]{width, height};
        }
        // 没存过尺寸时的默认值：1920×1080
        return new int[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
