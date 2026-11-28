package com.yeapguy.volumescroll;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent storage for step statistics: daily step counts, all-time total,
 * walk time, step-speed records (steps per second), per-minute step buckets
 * of the current day (for the minute graph and the last-minute speed) and
 * rolling-period totals.
 */
public final class StepStatsStore {
    private static final int KEEP_DAYS = 430;
    /** Days shown in the speed graph: this day + the last year. */
    public static final int SPEED_GRAPH_DAYS = 365;
    private static final String KEY_ALL_TIME = "all_time";
    private static final String KEY_DAILY_PREFIX = "daily_";
    /** Per-day walking time, needed for the per-day average speed (s/min). */
    private static final String KEY_DAILY_WALK_PREFIX = "daily_walk_";
    private static final String LOG_FILE_NAME = "loki_log.txt";
    private static final String LOG_HEADER = "date,steps,speed(s/min)";
    private static final String KEY_DAY_BASELINE = "day_baseline";
    private static final String KEY_LAST_CUMULATIVE = "last_cumulative";
    private static final String KEY_ROLLED_DATE = "rolled_date";
    private static final String KEY_STEPS_TODAY = "steps_today";
    private static final String KEY_SPEED_BEST_ALL = "speed_best_all";
    private static final String KEY_SPEED_BEST_DATE = "speed_best_date";
    private static final String KEY_SPEED_BEST_TODAY = "speed_best_today";
    private static final String KEY_WALK_TODAY_MS = "walk_today_ms";
    private static final String KEY_SWALLOWED_TODAY = "swallowed_today";
    private static final String KEY_MINUTES_PREFIX = "minutes_";
    private static final int MINUTES_PER_DAY = 24 * 60;
    /** The day's step counter only starts after this many steps (sensor blips don't count). */
    public static final int STEPS_START_THRESHOLD = 10;
    private static final String PREFS_NAME = "step_stats";

    private final Context appContext;
    private final SharedPreferences prefs;

    /** In-memory per-minute buckets of today (minute of day -> steps). */
    private int[] minuteSteps;
    private String minuteStepsDay;

    public StepStatsStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        rollDayIfNeeded();
    }

    public static boolean hasPedometer(Context context) {
        SensorManager sm = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    public synchronized void applyCounterValue(long cumulative) {
        applyCounterValue(cumulative, System.currentTimeMillis());
    }

    /**
     * Same as above; the counted steps are also bucketed into the minute of
     * {@code eventMillis} (used by the minute graph and last-minute speed).
     */
    public synchronized void applyCounterValue(long cumulative, long eventMillis) {
        long last = this.prefs.getLong(KEY_LAST_CUMULATIVE, Long.MIN_VALUE);
        if (last == Long.MIN_VALUE) {
            this.prefs.edit()
                    .putLong(KEY_LAST_CUMULATIVE, cumulative)
                    .putLong(KEY_DAY_BASELINE, cumulative)
                    .apply();
            return;
        }
        rollDayIfNeeded();
        if (cumulative < last) {
            // Device rebooted or the sensor counter was reset: rebase the baseline
            // so today's count survives, without inventing steps.
            long rebased = cumulative - this.prefs.getLong(KEY_STEPS_TODAY, 0L);
            this.prefs.edit()
                    .putLong(KEY_LAST_CUMULATIVE, cumulative)
                    .putLong(KEY_DAY_BASELINE, Math.min(rebased, cumulative))
                    .apply();
            return;
        }
        long delta = cumulative - last;
        if (delta <= 0) {
            return;
        }
        long counted = swallowStartSteps(delta);
        String today = today();
        long stepsToday = this.prefs.getLong(KEY_STEPS_TODAY, 0L) + counted;
        String dailyKey = KEY_DAILY_PREFIX + today;
        SharedPreferences.Editor e = this.prefs.edit();
        e.putLong(KEY_LAST_CUMULATIVE, cumulative);
        e.putLong(KEY_STEPS_TODAY, stepsToday);
        e.putLong(dailyKey, this.prefs.getLong(dailyKey, 0L) + counted);
        e.putLong(KEY_ALL_TIME, this.prefs.getLong(KEY_ALL_TIME, 0L) + counted);
        e.apply();
        if (counted > 0) {
            addStepsToMinute(minuteOfDay(eventMillis), (int) Math.min(counted, 500L));
        }
    }

    /**
     * The day's counter "starts after 10 steps": the first
     * {@link #STEPS_START_THRESHOLD} steps of a day are swallowed (they also
     * never reach the daily/all-time totals), everything above counts.
     */
    private long swallowStartSteps(long delta) {
        long swallowed = this.prefs.getLong(KEY_SWALLOWED_TODAY, 0L);
        if (swallowed >= STEPS_START_THRESHOLD) {
            return delta;
        }
        long remaining = STEPS_START_THRESHOLD - swallowed;
        long counted;
        if (delta <= remaining) {
            swallowed += delta;
            counted = 0;
        } else {
            counted = delta - remaining;
            swallowed = STEPS_START_THRESHOLD;
        }
        this.prefs.edit().putLong(KEY_SWALLOWED_TODAY, swallowed).apply();
        return counted;
    }

    public synchronized void addWalkTime(long millis) {
        if (millis <= 0) {
            return;
        }
        rollDayIfNeeded();
        String today = today();
        this.prefs.edit()
                .putLong(KEY_WALK_TODAY_MS, this.prefs.getLong(KEY_WALK_TODAY_MS, 0L) + millis)
                .putLong(KEY_DAILY_WALK_PREFIX + today,
                        this.prefs.getLong(KEY_DAILY_WALK_PREFIX + today, 0L) + millis)
                .apply();
    }

    /** Records a live steps-per-second observation, keeping daily and all-time records. */
    public synchronized void recordSpeed(float stepsPerSecond) {
        if (!(stepsPerSecond > 0f)) {
            return;
        }
        rollDayIfNeeded();
        String today = today();
        SharedPreferences.Editor e = this.prefs.edit();
        if (!today.equals(this.prefs.getString(KEY_SPEED_BEST_DATE, null))) {
            e.putString(KEY_SPEED_BEST_DATE, today);
            e.putFloat(KEY_SPEED_BEST_TODAY, 0f);
        }
        if (stepsPerSecond > this.prefs.getFloat(KEY_SPEED_BEST_TODAY, 0f)) {
            e.putFloat(KEY_SPEED_BEST_TODAY, stepsPerSecond);
        }
        if (stepsPerSecond > this.prefs.getFloat(KEY_SPEED_BEST_ALL, 0f)) {
            e.putFloat(KEY_SPEED_BEST_ALL, stepsPerSecond);
        }
        e.apply();
    }

    public synchronized float getBestSpeedToday() {
        rollDayIfNeeded();
        String today = today();
        if (!today.equals(this.prefs.getString(KEY_SPEED_BEST_DATE, null))) {
            return 0f;
        }
        return this.prefs.getFloat(KEY_SPEED_BEST_TODAY, 0f);
    }

    public synchronized float getBestSpeedAllTime() {
        return this.prefs.getFloat(KEY_SPEED_BEST_ALL, 0f);
    }

    public synchronized long getStepsToday() {
        rollDayIfNeeded();
        return this.prefs.getLong(KEY_STEPS_TODAY, 0L);
    }

    public synchronized long getWalkTimeTodayMs() {
        rollDayIfNeeded();
        return this.prefs.getLong(KEY_WALK_TODAY_MS, 0L);
    }

    // ------------------------------------------------------------------
    // Per-minute step buckets of today (minute graph / last-minute speed)
    // ------------------------------------------------------------------

    /** Steps per minute-of-day for today; index 0 = 00:00, array length 1440. */
    public synchronized int[] getMinuteStepsToday() {
        return getMinuteArrayLocked().clone();
    }

    /** Adds {@code count} steps to a minute-of-day bucket (memory + prefs). */
    private void addStepsToMinute(int minute, int count) {
        if (count <= 0) {
            return;
        }
        if (minute < 0) {
            minute = 0;
        } else if (minute >= MINUTES_PER_DAY) {
            minute = MINUTES_PER_DAY - 1;
        }
        int[] arr = getMinuteArrayLocked();
        arr[minute] += count;
        this.prefs.edit().putString(KEY_MINUTES_PREFIX + today(), encodeMinutes(arr)).apply();
    }

    private int[] getMinuteArrayLocked() {
        String today = today();
        if (this.minuteSteps == null || !today.equals(this.minuteStepsDay)) {
            this.minuteSteps = new int[MINUTES_PER_DAY];
            this.minuteStepsDay = today;
            String raw = this.prefs.getString(KEY_MINUTES_PREFIX + today, null);
            if (raw != null && !raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (int i = 0; i < parts.length && i < MINUTES_PER_DAY; i++) {
                    try {
                        this.minuteSteps[i] = Integer.parseInt(parts[i]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return this.minuteSteps;
    }

    private static String encodeMinutes(int[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 2);
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static int minuteOfDay(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    // ------------------------------------------------------------------
    // Rolling periods: totals, days in period and average speed
    // ------------------------------------------------------------------

    /** Steps / walking time / day count of one period. */
    public static final class PeriodStats {
        public long steps;
        public long walkMs;
        public int days;
    }

    /** Stats of the last {@code days} days including today (rolling window). */
    public synchronized PeriodStats getRollingPeriod(int days) {
        Calendar start = zeroedToday();
        start.add(Calendar.DAY_OF_MONTH, -(days - 1));
        return collectPeriod(start.getTimeInMillis(), days);
    }

    /** Stats over all recorded days (earliest stored day through today). */
    public synchronized PeriodStats getAllTimePeriod() {
        long earliest = Long.MAX_VALUE;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (String key : this.prefs.getAll().keySet()) {
            if (!key.startsWith(KEY_DAILY_PREFIX)) {
                continue;
            }
            try {
                Date day = fmt.parse(key.substring(KEY_DAILY_PREFIX.length()));
                if (day != null && day.getTime() < earliest) {
                    earliest = day.getTime();
                }
            } catch (Exception ignored) {
            }
        }
        if (earliest == Long.MAX_VALUE) {
            return new PeriodStats();
        }
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(earliest);
        zero(start);
        long todayMillis = zeroedToday().getTimeInMillis();
        int days = (int) ((todayMillis - start.getTimeInMillis()) / 86400000L) + 1;
        if (days < 1) {
            days = 1;
        }
        return collectPeriod(start.getTimeInMillis(), days);
    }

    private PeriodStats collectPeriod(long fromMillis, int days) {
        PeriodStats stats = new PeriodStats();
        stats.days = days;
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(fromMillis);
        zero(cursor);
        Calendar end = zeroedToday();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        while (!cursor.after(end)) {
            String key = fmt.format(cursor.getTime());
            stats.steps += this.prefs.getLong(KEY_DAILY_PREFIX + key, 0L);
            stats.walkMs += this.prefs.getLong(KEY_DAILY_WALK_PREFIX + key, 0L);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return stats;
    }

    public synchronized long getStepsForDay(String dayKey) {
        return this.prefs.getLong(KEY_DAILY_PREFIX + dayKey, 0L);
    }

    /** Walking time recorded for a given day ("yyyy-MM-dd"), 0 when unknown. */
    public synchronized long getWalkTimeForDay(String dayKey) {
        return this.prefs.getLong(KEY_DAILY_WALK_PREFIX + dayKey, 0L);
    }

    /** Average walking speed of a given day in steps per minute (0 when no walk time). */
    public synchronized float getAvgSpeedForDay(String dayKey) {
        return avgSpeedOf(dayKey);
    }

    /** Average walking speed of today in steps per minute (0 when no walk time yet). */
    public synchronized float getAvgSpeedToday() {
        rollDayIfNeeded();
        return avgSpeedOf(today());
    }

    /** Today as the storage key format, "yyyy-MM-dd". */
    public synchronized String getTodayKey() {
        rollDayIfNeeded();
        return today();
    }

    /**
     * Fills {@code out} with the daily average walking speed (steps per
     * minute) of the last {@code out.length} days, oldest first, today last.
     * Days without recorded walk time yield 0 (a gap in the graph).
     */
    public synchronized void fillDailyAvgSpeeds(float[] out) {
        rollDayIfNeeded();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cursor = zeroedToday();
        cursor.add(Calendar.DAY_OF_MONTH, -(out.length - 1));
        for (int i = 0; i < out.length; i++) {
            out[i] = avgSpeedOf(fmt.format(cursor.getTime()));
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private float avgSpeedOf(String dayKey) {
        long walkMs = this.prefs.getLong(KEY_DAILY_WALK_PREFIX + dayKey, 0L);
        if (walkMs <= 0L) {
            return 0f;
        }
        long steps = this.prefs.getLong(KEY_DAILY_PREFIX + dayKey, 0L);
        return steps / (walkMs / 60000f);
    }

    public synchronized long getAllTime() {
        return this.prefs.getLong(KEY_ALL_TIME, 0L);
    }

    private void rollDayIfNeeded() {
        String today = today();
        String previousDay = this.prefs.getString(KEY_ROLLED_DATE, null);
        if (today.equals(previousDay)) {
            return;
        }
        if (previousDay != null) {
            // New day: the finished day is complete — write its log line.
            appendDailyLog(previousDay);
        }
        this.prefs.edit()
                .putString(KEY_ROLLED_DATE, today)
                .putLong(KEY_STEPS_TODAY, 0L)
                .putLong(KEY_SWALLOWED_TODAY, 0L)
                .putLong(KEY_DAY_BASELINE, this.prefs.getLong(KEY_LAST_CUMULATIVE, 0L))
                .putLong(KEY_WALK_TODAY_MS, 0L)
                .putString(KEY_SPEED_BEST_DATE, today)
                .putFloat(KEY_SPEED_BEST_TODAY, 0f)
                .apply();
        pruneOldDays();
    }

    private void pruneOldDays() {
        Calendar keepFrom = zeroedToday();
        keepFrom.add(Calendar.DAY_OF_MONTH, -KEEP_DAYS);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SharedPreferences.Editor e = this.prefs.edit();
        for (String key : this.prefs.getAll().keySet()) {
            String datePart = null;
            if (key.startsWith(KEY_DAILY_PREFIX)) {
                datePart = key.substring(KEY_DAILY_PREFIX.length());
            } else if (key.startsWith(KEY_MINUTES_PREFIX)) {
                datePart = key.substring(KEY_MINUTES_PREFIX.length());
            }
            if (datePart != null) {
                try {
                    Date day = fmt.parse(datePart);
                    if (day == null || day.before(keepFrom.getTime())) {
                        e.remove(key);
                    }
                } catch (Exception parseError) {
                    e.remove(key);
                }
            }
        }
        e.apply();
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis()));
    }

    private static Calendar zeroedToday() {
        Calendar cal = Calendar.getInstance();
        zero(cal);
        return cal;
    }

    private static void zero(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /** Duration without seconds, e.g. "42 min" or "2 h 05 min". */
    public static String formatDuration(long millis) {
        long totalMinutes = millis / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d h %02d min", hours, minutes);
        }
        return String.format(Locale.US, "%d min", minutes);
    }

    /** Total minutes only, e.g. "125 min" — used by the walking notification. */
    public static String formatMinutesOnly(long millis) {
        return String.format(Locale.US, "%d min", millis / 60000L);
    }

    // ------------------------------------------------------------------
    // Daily log file: one "date,steps,speed(s/min)" line per finished day
    // ------------------------------------------------------------------

    /** The finished {@code dayKey} is complete: append its line to the log file. */
    private void appendDailyLog(String dayKey) {
        long steps = this.prefs.getLong(KEY_DAILY_PREFIX + dayKey, 0L);
        String walkKey = KEY_DAILY_WALK_PREFIX + dayKey;
        long walkMs;
        if (this.prefs.contains(walkKey)) {
            walkMs = this.prefs.getLong(walkKey, 0L);
        } else {
            // Days recorded before per-day walk time existed: the not-yet-
            // reset walk-today counter still holds that day's total.
            walkMs = this.prefs.getLong(KEY_WALK_TODAY_MS, 0L);
            if (walkMs > 0L) {
                this.prefs.edit().putLong(walkKey, walkMs).apply();
            }
        }
        long speed = walkMs > 0L
                ? Math.round(steps / (walkMs / 60000f))
                : 0L;

        File dir = this.appContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = this.appContext.getFilesDir();
        }
        if (dir == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            File logFile = new File(dir, LOG_FILE_NAME);
            boolean writeHeader = !logFile.exists();
            out = new FileOutputStream(logFile, true);
            StringBuilder line = new StringBuilder();
            if (writeHeader) {
                line.append(LOG_HEADER).append('\n');
            }
            line.append(dayKey).append(',')
                    .append(steps).append(',')
                    .append(speed).append('\n');
            out.write(line.toString().getBytes("UTF-8"));
            out.flush();
        } catch (IOException | SecurityException ignored) {
            // Logging must never break step counting.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
