package com.decibelmeter.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 声音频段（频谱）柱状图：一列对应一个 1/1 倍频程，从左到右频率升高。
 *
 * 显示思路（入门）：
 * 1. 纵轴是自动量程。取当前最高频段再上浮一点作为顶部刻度，向下 60 dB 作为底部，
 *    这样安静环境和嘈杂环境都能看清柱形差异，刻度值不会剧烈跳动（用平滑过渡）。
 * 2. 柱高按 dB 线性映射，10 dB 大约等于视觉上的"高一倍"，符合分贝的对数性质。
 * 3. 每根柱子上方保留一条细横线作为峰值记忆（peak hold），下降慢、上升快，方便看出瞬时尖峰。
 * 4. 能量最高的那个频段用醒目颜色标出，即"主频段"。
 */
public class SpectrumView extends View {

    private static final int BAND_COUNT = SpectrumAnalyzer.BAND_CENTERS_HZ.length;
    private static final float RANGE_DB = 60f;   // 纵轴显示的分贝跨度
    private static final double ATTACK = 0.45;   // 声级上升时的平滑系数（快）
    private static final double RELEASE = 0.12;  // 声级下降时的平滑系数（慢）
    private static final double PEAK_FALL_DB = 0.6; // 峰值记忆线每帧回落的 dB

    private final double[] levels = new double[BAND_COUNT];
    private final double[] peaks = new double[BAND_COUNT];
    private final String[] labels = new String[BAND_COUNT];

    private float scaleTop = 120f; // 纵轴顶部 dB 值，随数据平滑变化

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean hasData;

    public SpectrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < BAND_COUNT; i++) {
            levels[i] = -120;
            peaks[i] = -120;
            labels[i] = bandLabel(SpectrumAnalyzer.BAND_CENTERS_HZ[i]);
        }
        labelPaint.setTextSize(
                getResources().getDimensionPixelSize(R.dimen.meter_tick_text));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** 频率标签：1000 Hz 以上缩写成 1k、2k，避免横排文字过挤。 */
    private static String bandLabel(int hz) {
        return hz < 1000 ? String.valueOf(hz) : (hz / 1000) + "k";
    }

    /**
     * 更新各频段声级（单位 dB，与主数值同一校准基准）。
     * 数组长度须等于 BAND_COUNT，顺序同 SpectrumAnalyzer.BAND_CENTERS_HZ。
     */
    public void setLevels(double[] db) {
        for (int i = 0; i < BAND_COUNT; i++) {
            double target = db[i];
            double alpha = target > levels[i] ? ATTACK : RELEASE;
            levels[i] += (target - levels[i]) * alpha;
            peaks[i] = Math.max(peaks[i] - PEAK_FALL_DB, levels[i]);
        }
        hasData = true;
        invalidate();
    }

    public void clear() {
        for (int i = 0; i < BAND_COUNT; i++) {
            levels[i] = -120;
            peaks[i] = -120;
        }
        hasData = false;
        invalidate();
    }

    /** 能量最高的频段序号，供界面文字提示"主频段"使用。 */
    public int dominantBandIndex() {
        int best = 0;
        for (int i = 1; i < BAND_COUNT; i++) {
            if (levels[i] > levels[best]) {
                best = i;
            }
        }
        return best;
    }

    public double dominantBandLevel() {
        return levels[dominantBandIndex()];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        gridPaint.setColor(night ? 0xFF4A4A4A : 0xFFD5D5D5);
        labelPaint.setColor(night ? 0xFFAAAAAA : 0xFF777777);

        float w = getWidth(), h = getHeight();
        float labelH = labelPaint.getTextSize() + 6f;
        float plotH = h - labelH;
        float slot = w / (float) BAND_COUNT;
        float barW = slot * 0.66f;

        // 自动量程：顶部跟随最高频段，变化时向上快、向下慢，避免刻度抖动。
        float highest = -120f;
        int dominant = hasData ? dominantBandIndex() : -1;
        if (hasData) {
            highest = (float) Math.max(highest, levels[dominant]);
        }
        float desired = (float) Math.ceil((highest + 4) / 10f) * 10f;
        scaleTop += desired > scaleTop ? (desired - scaleTop) * 0.5f
                : (desired - scaleTop) * 0.05f;
        float scaleBottom = Math.max(0f, scaleTop - RANGE_DB);
        float span = Math.max(1f, scaleTop - scaleBottom);

        // 4 条水平网格线，把量程均分成 3 段，便于目测 dB 差。
        for (int i = 0; i <= 3; i++) {
            float y = plotH * i / 3f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        for (int b = 0; b < BAND_COUNT; b++) {
            float cx = slot * b + slot / 2f;
            canvas.drawText(labels[b], cx, h - 4f, labelPaint);
            if (!hasData) {
                continue;
            }
            float top = plotY(levels[b], scaleTop, span, plotH);
            if (top >= plotH - 0.5f) {
                continue; // 低于量程下限的频段不画柱体，只留标签
            }
            float left = cx - barW / 2f;
            barPaint.setColor(b == dominant
                    ? (night ? 0xFFFFD54F : 0xFFF9A825)
                    : (night ? 0xFF4DB6AC : 0xFF26A69A));
            canvas.drawRect(left, top, left + barW, plotH, barPaint);

            // 峰值记忆线
            float peakTop = plotY(peaks[b], scaleTop, span, plotH);
            if (peakTop < top) {
                peakPaint.setColor(barPaint.getColor());
                peakPaint.setAlpha(150);
                canvas.drawRect(left, peakTop, left + barW, peakTop + 3f, peakPaint);
                peakPaint.setAlpha(255);
            }
        }
    }

    /** 把 dB 值换算成画布纵坐标（值越大越靠上）。 */
    private static float plotY(double db, float top, float span, float plotH) {
        double v = Math.max(top - span, Math.min(top, db));
        return plotH * (float) (1 - (v - (top - span)) / span);
    }
}
