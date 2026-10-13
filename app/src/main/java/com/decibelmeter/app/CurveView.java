package com.decibelmeter.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 最近 60 秒分贝滚动曲线，纵轴按数据自动量程（5 dB 步进）。
 */
public class CurveView extends View {

    private static final int CAP = 1600;
    private static final long WINDOW_MS = 60_000L;

    private final float[] values = new float[CAP];
    private final long[] times = new long[CAP];
    private int start;
    private int size;

    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xFF26A69A);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x3326A69A);
        labelPaint.setTextSize(
                getResources().getDimensionPixelSize(R.dimen.meter_tick_text));
    }

    public void addPoint(float db, long timestampMs) {
        if (size == CAP) {
            start = (start + 1) % CAP;
            size--;
        }
        int idx = (start + size) % CAP;
        values[idx] = db;
        times[idx] = timestampMs;
        size++;
        invalidate();
    }

    public void clear() {
        start = 0;
        size = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        gridPaint.setColor(night ? 0xFF4A4A4A : 0xFFD5D5D5);
        labelPaint.setColor(night ? 0xFFAAAAAA : 0xFF777777);

        float w = getWidth(), h = getHeight();
        float labelW = labelPaint.measureText("120 dB") + 8f;
        float bottomH = labelPaint.getTextSize() + 6f;
        float plotW = w - labelW, plotH = h - bottomH;

        long now = System.currentTimeMillis();
        long oldest = now - WINDOW_MS;
        while (size > 0 && times[start] < oldest) {
            start = (start + 1) % CAP;
            size--;
        }

        // 水平网格
        for (int i = 0; i <= 4; i++) {
            float y = plotH * i / 4f;
            canvas.drawLine(labelW, y, w, y, gridPaint);
        }
        // 垂直网格：每 10 秒
        for (int i = 0; i <= 6; i++) {
            float x = labelW + plotW * i / 6f;
            canvas.drawLine(x, 0, x, plotH, gridPaint);
        }
        canvas.drawLine(labelW, plotH, w, plotH, gridPaint);

        canvas.drawText("-60s", labelW + 2, h - 4, labelPaint);
        canvas.drawText("-30s", labelW + plotW / 2f + 2, h - 4, labelPaint);
        canvas.drawText("0", w - labelPaint.measureText("0") - 2, h - 4, labelPaint);

        if (size < 2) {
            return;
        }

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            float v = values[(start + i) % CAP];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float lo = (float) Math.floor((min - 2) / 5f) * 5f;
        float hi = lo + 20f;
        if (max + 2 > hi) {
            hi = (float) Math.ceil((max + 2) / 5f) * 5f;
            lo = hi - 20f;
        }
        if (lo < 0) {
            lo = 0;
            hi = 20;
        }

        for (int i = 0; i <= 4; i++) {
            float db = hi - (hi - lo) * i / 4f;
            float y = plotH * i / 4f;
            String label = String.format(java.util.Locale.US, "%.0f dB", db);
            canvas.drawText(label, 2, y + labelPaint.getTextSize() * 0.4f, labelPaint);
        }

        linePath.rewind();
        fillPath.rewind();
        boolean first = true;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % CAP;
            float x = labelW + plotW * (times[idx] - oldest) / (float) WINDOW_MS;
            float v = Math.max(lo, Math.min(hi, values[idx]));
            float y = plotH * (1 - (v - lo) / (hi - lo));
            if (first) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, plotH);
                linePath.lineTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        int lastIdx = (start + size - 1) % CAP;
        float lastX = labelW + plotW * (times[lastIdx] - oldest) / (float) WINDOW_MS;
        fillPath.lineTo(lastX, plotH);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
