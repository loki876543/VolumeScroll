package com.yeapguy.volumescroll;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minute-by-minute speed graph of the current day. Consecutive active minutes
 * form a "stint"; idle stretches longer than an hour are collapsed into a gap
 * labelled with the start time of the following stint. The vertical scale
 * starts at the low warning limit, so only speeds above it get a bar.
 */
public class MinuteGraphView extends View {
    private static final int MINUTES_PER_DAY = 24 * 60;
    /** Idle runs longer than this are removed from the graph. */
    private static final int GAP_MINUTES = 60;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int[] minuteSteps = new int[MINUTES_PER_DAY];
    private int nowMinute = -1;
    private int lowLimit = 90;
    private final List<Stint> stints = new ArrayList<>();
    private boolean colorsInitialized = false;

    private static final class Stint {
        final int start;
        final int end;

        Stint(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int minutes() {
            return this.end - this.start + 1;
        }
    }

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
     * @param lowLimit  low warning limit; the graph's vertical scale starts here
     */
    public void setData(int[] steps, int nowMinute, int lowLimit) {
        if (steps != null && steps.length == MINUTES_PER_DAY) {
            this.minuteSteps = steps.clone();
        } else {
            this.minuteSteps = new int[MINUTES_PER_DAY];
        }
        this.nowMinute = nowMinute;
        this.lowLimit = lowLimit;
        buildStints();
        invalidate();
    }

    /** Splits the day into active runs; idle runs over an hour become gaps. */
    private void buildStints() {
        this.stints.clear();
        int i = 0;
        while (i < MINUTES_PER_DAY) {
            while (i < MINUTES_PER_DAY && this.minuteSteps[i] <= 0) {
                i++;
            }
            if (i >= MINUTES_PER_DAY) {
                break;
            }
            int start = i;
            int lastActive = i;
            int idle = 0;
            i++;
            while (i < MINUTES_PER_DAY) {
                if (this.minuteSteps[i] > 0) {
                    lastActive = i;
                    idle = 0;
                } else if (++idle > GAP_MINUTES) {
                    break;
                }
                i++;
            }
            this.stints.add(new Stint(start, lastActive));
            while (i < MINUTES_PER_DAY && this.minuteSteps[i] <= 0) {
                i++;
            }
        }
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
        int plotTop = Math.round(this.labelPaint.getTextSize()) + dp(4);
        if (width <= 0 || baseline - plotTop <= dp(4)) {
            return;
        }
        canvas.drawLine(0, baseline, width, baseline, this.gridPaint);
        if (this.stints.isEmpty()) {
            return;
        }

        int max = this.lowLimit + 1;
        for (int value : this.minuteSteps) {
            if (value > max) {
                max = value;
            }
        }

        int activeMinutes = 0;
        for (Stint stint : this.stints) {
            activeMinutes += stint.minutes();
        }
        if (activeMinutes <= 0) {
            return;
        }

        int gapWidth = dp(40);
        int gapCount = this.stints.size() - 1;
        int available = width - gapCount * gapWidth;
        if (available < dp(20)) {
            gapWidth = 0;
            available = width;
        }
        float slot = (float) available / activeMinutes;
        float lineHeight = baseline - plotTop;

        float[] stintX = new float[this.stints.size()];
        float cursor = 0f;
        for (int i = 0; i < this.stints.size(); i++) {
            Stint stint = this.stints.get(i);
            stintX[i] = cursor;
            float endX = cursor + stint.minutes() * slot;
            canvas.drawLine(cursor, plotTop, cursor, baseline, this.gridPaint);
            String label = formatTime(stint.start);
            float labelWidth = this.labelPaint.measureText(label);
            float labelX = Math.max(0f, Math.min(cursor + dp(2), width - labelWidth));
            canvas.drawText(label, labelX, plotTop - dp(2), this.labelPaint);
            for (int minute = stint.start; minute <= stint.end; minute++) {
                int steps = this.minuteSteps[minute];
                if (steps <= this.lowLimit) {
                    continue;
                }
                float x = cursor + (minute - stint.start) * slot;
                float lineH = Math.max(dp(2),
                        ((float) (steps - this.lowLimit) / (max - this.lowLimit)) * lineHeight);
                canvas.drawLine(x, baseline - lineH, x, baseline, this.linePaint);
            }
            cursor = endX + gapWidth;
        }

        if (this.nowMinute >= 0 && this.nowMinute < MINUTES_PER_DAY) {
            float x = nowX(stintX, slot, gapWidth);
            canvas.drawLine(x, plotTop, x, baseline, this.nowPaint);
        }
    }

    /** Maps the current minute into the compressed layout. */
    private float nowX(float[] stintX, float slot, int gapWidth) {
        for (int i = 0; i < this.stints.size(); i++) {
            Stint stint = this.stints.get(i);
            if (this.nowMinute >= stint.start && this.nowMinute <= stint.end) {
                return stintX[i] + (this.nowMinute - stint.start) * slot;
            }
            if (this.nowMinute < stint.start) {
                return i == 0 ? 0f : stintX[i] - gapWidth / 2f;
            }
        }
        return getWidth();
    }

    private static String formatTime(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
