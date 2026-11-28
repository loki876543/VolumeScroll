package com.yeapguy.volumescroll;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Minute-by-minute speed graph of the current day: one vertical line per
 * minute of the day (00:00 … 23:59), the height of the line is that
 * minute's speed in steps per minute. Quiet minutes leave gaps, hour marks
 * give orientation, a dim vertical marker shows "now". Pure Canvas drawing —
 * no chart library needed.
 */
public class MinuteGraphView extends View {
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int[] minuteSteps = new int[MINUTES_PER_DAY];
    private int nowMinute = -1;
    private boolean colorsInitialized = false;

    public MinuteGraphView(Context context) {
        this(context, null);
    }

    public MinuteGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.labelPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 10f, getResources().getDisplayMetrics()));
    }

    /**
     * @param steps     steps per minute-of-day, index 0 = 00:00
     * @param nowMinute current minute of day (-1 = don't draw the marker)
     */
    public void setData(int[] steps, int nowMinute) {
        if (steps != null && steps.length == MINUTES_PER_DAY) {
            this.minuteSteps = steps.clone();
        } else {
            this.minuteSteps = new int[MINUTES_PER_DAY];
        }
        this.nowMinute = nowMinute;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!this.colorsInitialized) {
            this.colorsInitialized = true;
            int accent = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorPrimary);
            int dim = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            this.linePaint.setColor(accent);
            this.nowPaint.setColor(dim);
            this.gridPaint.setColor(dim);
            this.gridPaint.setAlpha(60);
            this.labelPaint.setColor(dim);
        }

        int width = getWidth();
        int height = getHeight();
        int baseline = height - dp(2);
        int plotTop = Math.round(this.labelPaint.getTextSize()) + dp(2);
        if (width <= 0 || baseline - plotTop <= dp(4)) {
            return;
        }

        int max = 1;
        for (int value : this.minuteSteps) {
            if (value > max) {
                max = value;
            }
        }

        // Hour marks every 3 hours with labels on 0/6/12/18.
        float slot = (float) width / MINUTES_PER_DAY;
        for (int hour = 0; hour < 24; hour += 3) {
            float x = hour * 60 * slot;
            canvas.drawLine(x, plotTop, x, baseline, this.gridPaint);
            if (hour % 6 == 0) {
                String label = hour + "h";
                float labelWidth = this.labelPaint.measureText(label);
                float labelX = Math.max(0f, Math.min(x + dp(2), width - labelWidth));
                canvas.drawText(label, labelX, plotTop - dp(2), this.labelPaint);
            }
        }
        canvas.drawLine(0, baseline, width, baseline, this.gridPaint);

        // One vertical line per active minute: height = that minute's speed.
        float lineHeight = baseline - plotTop;
        for (int minute = 0; minute < MINUTES_PER_DAY; minute++) {
            int steps = this.minuteSteps[minute];
            if (steps <= 0) {
                continue;
            }
            float x = minute * slot;
            float lineH = Math.max(dp(2), ((float) steps / max) * lineHeight);
            canvas.drawLine(x, baseline - lineH, x, baseline, this.linePaint);
        }

        if (this.nowMinute >= 0 && this.nowMinute < MINUTES_PER_DAY) {
            float x = this.nowMinute * slot;
            canvas.drawLine(x, plotTop, x, baseline, this.nowPaint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
