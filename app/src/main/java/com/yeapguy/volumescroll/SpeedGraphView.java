package com.yeapguy.volumescroll;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Compact bar graph of the daily average walking speed (steps per minute)
 * over the last year: one hairline bar per day, today highlighted, the
 * yearly maximum labeled at the top. Days without recorded walk time are
 * gaps. Pure Canvas drawing — no chart library needed.
 */
public class SpeedGraphView extends View {
    private static final int GRAPH_DAYS = StepStatsStore.SPEED_GRAPH_DAYS;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] speeds = new float[0];
    private int todayIndex = -1;
    private boolean colorsInitialized = false;

    public SpeedGraphView(Context context) {
        this(context, null);
    }

    public SpeedGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.labelPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 10f, getResources().getDisplayMetrics()));
    }

    /**
     * @param speeds     daily average speeds in steps per minute, oldest first
     * @param todayIndex index of today's bar (highlighted)
     */
    public void setData(float[] speeds, int todayIndex) {
        this.speeds = speeds;
        this.todayIndex = todayIndex;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!this.colorsInitialized) {
            this.colorsInitialized = true;
            int base = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorPrimary);
            int accent = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorPrimary);
            int dim = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            this.barPaint.setColor(base);
            this.barPaint.setAlpha(150);
            this.todayPaint.setColor(accent);
            this.gridPaint.setColor(dim);
            this.gridPaint.setAlpha(70);
            this.labelPaint.setColor(dim);
        }

        int width = getWidth();
        int height = getHeight();
        // Top strip holds the yearly max label; bars grow from the baseline.
        int baseline = height;
        int plotTop = Math.round(this.labelPaint.getTextSize()) + dp(2);
        if (width <= 0 || baseline - plotTop <= dp(4) || this.speeds.length == 0) {
            return;
        }

        float max = 0f;
        for (float value : this.speeds) {
            if (value > max) {
                max = value;
            }
        }
        if (max < 1f) {
            max = 1f;
        }

        // Gridline at the max level, its value labeled in the top strip.
        canvas.drawLine(0, plotTop, width, plotTop, this.gridPaint);
        canvas.drawLine(0, baseline - dp(1), width, baseline - dp(1), this.gridPaint);
        canvas.drawText(String.valueOf(Math.round(max)), 0f, plotTop - dp(2), this.labelPaint);

        float slot = (float) width / this.speeds.length;
        float barWidth = Math.max(1f, slot - 1f);
        int maxBarHeight = baseline - plotTop - dp(2);
        for (int i = 0; i < this.speeds.length; i++) {
            float value = this.speeds[i];
            if (value <= 0f) {
                continue;
            }
            float barHeight = Math.max(dp(2), (value / max) * maxBarHeight);
            float left = i * slot;
            Paint paint = (i == this.todayIndex) ? this.todayPaint : this.barPaint;
            canvas.drawRect(left, baseline - barHeight, left + barWidth, baseline, paint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
