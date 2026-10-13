package com.decibelmeter.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 30–120 dB 横向刻度条。
 */
public class MeterView extends View {

    static final float MIN_DB = 30f;
    static final float MAX_DB = 120f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float level = MIN_DB;
    private boolean dark;

    public MeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        tickPaint.setStrokeWidth(2f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLevel(float db) {
        level = Math.max(MIN_DB, Math.min(MAX_DB, db));
        invalidate();
    }

    private static int colorFor(float db) {
        if (db < 60) {
            return Color.rgb(76, 175, 80);
        }
        if (db < 75) {
            float t = (db - 60) / 15f;
            return Color.rgb(76 + (int) (175 * t), 175, 80 - (int) (60 * t));
        }
        if (db < 90) {
            float t = (db - 75) / 15f;
            return Color.rgb(251, 192 - (int) (60 * t), 45);
        }
        float t = Math.min(1f, (db - 90) / 25f);
        return Color.rgb(244 - (int) (30 * t), 67 + (int) (30 * t), 54 + (int) (20 * t));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dark = !isNightMode();
        int textColor = dark ? 0xFF333333 : 0xFFE0E0E0;
        trackPaint.setColor(dark ? 0xFFE0E0E0 : 0xFF3A3A3A);
        tickPaint.setColor(dark ? 0xFF9E9E9E : 0xFF8A8A8A);
        textPaint.setColor(textColor);

        float pad = 4f;
        float barHeight = getHeight() * 0.45f;
        float top = 6f;
        rect.set(pad, top, getWidth() - pad, top + barHeight);
        float radius = barHeight / 2f;
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        float fraction = (level - MIN_DB) / (MAX_DB - MIN_DB);
        if (fraction > 0.003f) {
            fillPaint.setColor(colorFor(level));
            RectF fill = new RectF(rect.left, rect.top, rect.left + rect.width() * fraction, rect.bottom);
            canvas.drawRoundRect(fill, radius, radius, fillPaint);
        }

        textPaint.setTextSize(getResources().getDimensionPixelSize(R.dimen.meter_tick_text));
        float textY = rect.bottom + textPaint.getTextSize() + 10f;
        for (int db = (int) MIN_DB; db <= MAX_DB; db += 10) {
            float x = rect.left + rect.width() * (db - MIN_DB) / (MAX_DB - MIN_DB);
            canvas.drawLine(x, rect.bottom + 4f, x, rect.bottom + 12f, tickPaint);
            canvas.drawText(String.valueOf(db), x, textY, textPaint);
        }
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
