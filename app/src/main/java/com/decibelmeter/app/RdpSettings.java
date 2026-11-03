package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 内置远程桌面的连接偏好：码率档位（决定色深与是否接受有损编码）。
 *
 * 指针模式和桌面分辨率不在这里——它们在会话页工具栏上直接改，存在 freerdpcore 那一侧
 * （{@code ApplicationSettingsActivity} / {@link com.freerdp.freerdpcore.presentation.SessionResolution}），
 * 连接簿和会话页改的是同一个值，不必两处各存一份再想办法对齐。
 */
final class RdpSettings {

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

    private static final String PREFS = "rdp_display";
    private static final String KEY_COLOR_DEPTH = "color_depth";
    private static final String KEY_QUALITY = "quality_preset";

    private final SharedPreferences prefs;

    RdpSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 码率档位：省流 / 均衡 / 清晰。上一版只有色深，16 位按省流迁移。 */
    int qualityPreset() {
        if (prefs.contains(KEY_QUALITY)) {
            return clamp(prefs.getInt(KEY_QUALITY, PRESET_BALANCED), PRESET_SAVE, PRESET_SHARP);
        }
        return prefs.getInt(KEY_COLOR_DEPTH, DEPTH_CLEAR) == DEPTH_FAST
                ? PRESET_SAVE : PRESET_BALANCED;
    }

    /** 连接参数里的色深，由码率档位决定。 */
    int colorDepth() {
        return qualityPreset() == PRESET_SAVE ? DEPTH_FAST : DEPTH_CLEAR;
    }

    /** 省流/均衡接受 RemoteFX 有损编码；清晰档关掉它，换文字锐利，代价是载荷变大。 */
    boolean lossyCodecs() {
        return qualityPreset() != PRESET_SHARP;
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
