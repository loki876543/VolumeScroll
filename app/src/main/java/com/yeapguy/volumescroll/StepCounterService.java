package com.yeapguy.volumescroll;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Foreground service that listens to the hardware pedometer, keeps the
 * daily/weekly/monthly statistics up to date and shows them in a persistent
 * notification. Also computes the live walking speed in steps per second.
 */
public class StepCounterService extends Service implements SensorEventListener {
    private static final String CHANNEL_ID = "step_counter_silent";
    private static final int NOTIFICATION_ID = 41;
    private static final long MIN_NOTIF_REFRESH_INTERVAL_MS = 2000;
    private static final long WALK_IDLE_TIMEOUT_MS = 10000;
    /** A walk starts counting (and feeding speed stats) at this step of the session. */
    private static final int WALK_START_STEPS = 10;
    /** Speed display and history are sampled at most this often while walking. */
    private static final long SPEED_SAMPLE_INTERVAL_MS = 60_000L;
    /** Refresh the minutes-only walking time even when steps briefly pause. */
    private static final long WALK_MINUTES_TICK_MS = 30_000L;
    /** Window used to average recent steps into a steps-per-second rate. */
    private static final long SPEED_WINDOW_MS = 8000;
    /** Window for the "last minute" speed shown in the notification. */
    private static final long LAST_MINUTE_WINDOW_MS = 60 * 1000L;
    /** After this much silence the last-minute speed shows as no data. */
    private static final long LAST_MINUTE_MAX_AGE_MS = 90 * 1000L;
    /** After this much silence the live speed shows as 0 instead of a stale value. */
    private static final long LIVE_RATE_MAX_AGE_MS = 2500;
    /** The speed must stay above/below the limit this long before the warning sounds. */
    private static final long SPEED_WARN_SUSTAIN_MS = 5 * 60 * 1000L;
    /** While the breach continues the warning repeats with this period. */
    private static final long SPEED_WARN_REPEAT_MS = 5 * 60 * 1000L;
    /** How often the breach conditions are re-evaluated. */
    private static final long SPEED_WARN_CHECK_INTERVAL_MS = 5000L;

    private static final Object SPEED_LOCK = new Object();
    private static float sLiveStepsPerSecond = 0f;
    private static long sLastStepElapsed = 0L;

    private static final Object MINUTE_LOCK = new Object();
    private static final Deque<Long> sMinuteStepTimes = new ArrayDeque<>();
    private static long sLastMinuteStepElapsed = 0L;

    private SensorManager sensorManager;
    private StepStatsStore store;
    private SettingsRepository settingsRepository;
    private Runnable walkTimeoutRunnable;
    private Runnable walkMinutesTickRunnable;
    private Runnable speedWarnRunnable;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<Long> stepTimes = new ArrayDeque<>();
    private boolean listenersRegistered = false;
    private boolean stepDetectorAvailable = false;
    private long lastCounterSeen = Long.MIN_VALUE;
    private long walkSessionStartElapsed = 0;
    private long sessionFirstStepElapsed = 0;
    private int sessionSteps = 0;
    private long lastStepElapsed = 0;
    private long lastSpeedSampleElapsed = 0;
    private String cachedSpeedText = "\u2014";
    private long lastNotifRefreshElapsed = 0;
    private long highBreachStartElapsed = 0;
    private long lowBreachStartElapsed = 0;
    private long highNextWarnElapsed = 0;
    private long lowNextWarnElapsed = 0;
    private ToneGenerator toneGenerator;

    /** Latest walking speed in steps per second; 0 when not actively walking. */
    public static float getLiveStepsPerSecond() {
        synchronized (SPEED_LOCK) {
            if (sLastStepElapsed == 0L
                    || SystemClock.elapsedRealtime() - sLastStepElapsed > LIVE_RATE_MAX_AGE_MS) {
                return 0f;
            }
            return sLiveStepsPerSecond;
        }
    }

    /**
     * Steps in the last 60 seconds — the speed in steps per minute shown in
     * the notification. Returns -1 when no steps were seen recently enough.
     */
    public static int getLastMinuteSteps() {
        synchronized (MINUTE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            while (!sMinuteStepTimes.isEmpty()
                    && now - sMinuteStepTimes.peekFirst() > LAST_MINUTE_WINDOW_MS) {
                sMinuteStepTimes.removeFirst();
            }
            if (sLastMinuteStepElapsed == 0L
                    || now - sLastMinuteStepElapsed > LAST_MINUTE_MAX_AGE_MS) {
                return -1;
            }
            return sMinuteStepTimes.size();
        }
    }

    public static boolean hasPedometer(Context context) {
        return StepStatsStore.hasPedometer(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.store = new StepStatsStore(this);
        this.settingsRepository = new SettingsRepository(this);
        this.sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        registerListeners();
        scheduleWalkTimeoutCheck();
        scheduleSpeedWarnCheck();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (this.sensorManager != null && this.listenersRegistered) {
            this.sensorManager.unregisterListener(this);
        }
        this.listenersRegistered = false;
        this.handler.removeCallbacks(this.walkTimeoutRunnable);
        if (this.walkMinutesTickRunnable != null) {
            this.handler.removeCallbacks(this.walkMinutesTickRunnable);
        }
        if (this.speedWarnRunnable != null) {
            this.handler.removeCallbacks(this.speedWarnRunnable);
        }
        if (this.toneGenerator != null) {
            this.toneGenerator.release();
            this.toneGenerator = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // health = pedometer based service; permission declared in the manifest.
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerListeners() {
        if (this.listenersRegistered || this.sensorManager == null) {
            return;
        }
        Sensor counter = this.sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Sensor detector = this.sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (counter != null) {
            this.sensorManager.registerListener(this, counter, SensorManager.SENSOR_DELAY_UI);
        }
        if (detector != null) {
            this.sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_UI);
        }
        this.stepDetectorAvailable = detector != null;
        this.listenersRegistered = counter != null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length == 0) {
            return;
        }
        // Pedometer event time (elapsedRealtimeNanos base on API 28+). Batched
        // delivery (screen off / doze) must not dump minutes-old steps into
        // "now", so fall back to arrival time only on implausible stamps.
        long nowMs = SystemClock.elapsedRealtime();
        long eventMs = event.timestamp / 1_000_000L;
        long tsMs = (eventMs > 0L && eventMs <= nowMs + 60_000L) ? eventMs : nowMs;
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            // Bucket the steps into the wall-clock minute they happened in.
            this.store.applyCounterValue((long) event.values[0],
                    System.currentTimeMillis() + (tsMs - nowMs));
            if (!this.stepDetectorAvailable) {
                // No step detector on this device: feed the minute window from
                // the counter deltas instead.
                long current = (long) event.values[0];
                if (this.lastCounterSeen != Long.MIN_VALUE) {
                    long delta = current - this.lastCounterSeen;
                    if (delta > 0 && delta <= 50) {
                        synchronized (MINUTE_LOCK) {
                            for (int i = 0; i < delta; i++) {
                                sMinuteStepTimes.addLast(tsMs);
                            }
                            sLastMinuteStepElapsed = tsMs;
                        }
                    }
                }
                this.lastCounterSeen = current;
            }
            refreshNotificationThrottled();
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            onStepDetected(tsMs, nowMs);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void onStepDetected(long stepMs, long nowMs) {
        if (this.sessionFirstStepElapsed == 0L) {
            this.sessionFirstStepElapsed = nowMs;
            this.sessionSteps = 0;
        }
        this.sessionSteps++;
        this.lastStepElapsed = nowMs;

        // The first steps of a session are ignored: no walk timer, no speed
        // samples, no speed display until the threshold is reached.
        if (this.sessionSteps >= WALK_START_STEPS) {
            if (this.walkSessionStartElapsed == 0L) {
                this.walkSessionStartElapsed = nowMs;
                this.cachedSpeedText = "\u2014";
                scheduleWalkMinutesTick();
            }
            recordStepTime(stepMs);
        }
        scheduleWalkTimeoutCheck();
        refreshNotificationThrottled();
    }

    private void recordStepTime(long stepMs) {
        synchronized (MINUTE_LOCK) {
            sMinuteStepTimes.addLast(stepMs);
            sLastMinuteStepElapsed = stepMs;
            while (!sMinuteStepTimes.isEmpty()
                    && stepMs - sMinuteStepTimes.peekFirst() > LAST_MINUTE_WINDOW_MS) {
                sMinuteStepTimes.removeFirst();
            }
        }
        while (!this.stepTimes.isEmpty() && stepMs - this.stepTimes.peekFirst() > SPEED_WINDOW_MS) {
            this.stepTimes.removeFirst();
        }
        this.stepTimes.addLast(stepMs);
        if (this.stepTimes.size() < 2) {
            return;
        }
        long spanMs = stepMs - this.stepTimes.peekFirst();
        if (spanMs < 400) {
            // Too short to give a meaningful average; wait for more samples.
            return;
        }
        float rate = (this.stepTimes.size() - 1) / (spanMs / 1000f);
        synchronized (SPEED_LOCK) {
            sLiveStepsPerSecond = rate;
            sLastStepElapsed = stepMs;
        }
        sampleSpeed(stepMs, rate);
    }

    /** Speed history and the displayed value refresh at most once a minute. */
    private void sampleSpeed(long stepMs, float rate) {
        if (this.lastSpeedSampleElapsed != 0L
                && stepMs - this.lastSpeedSampleElapsed < SPEED_SAMPLE_INTERVAL_MS) {
            return;
        }
        this.lastSpeedSampleElapsed = stepMs;
        this.store.recordSpeed(rate);
        int lastMinute = getLastMinuteSteps();
        this.cachedSpeedText = lastMinute > 0 ? String.valueOf(lastMinute) : "\u2014";
    }

    private void scheduleWalkTimeoutCheck() {
        if (this.walkTimeoutRunnable == null) {
            this.walkTimeoutRunnable = this::checkWalkTimeout;
        }
        this.handler.removeCallbacks(this.walkTimeoutRunnable);
        this.handler.postDelayed(this.walkTimeoutRunnable, WALK_IDLE_TIMEOUT_MS + 250L);
    }

    private void scheduleWalkMinutesTick() {
        if (this.walkMinutesTickRunnable == null) {
            this.walkMinutesTickRunnable = this::walkMinutesTick;
        }
        this.handler.removeCallbacks(this.walkMinutesTickRunnable);
        this.handler.postDelayed(this.walkMinutesTickRunnable, WALK_MINUTES_TICK_MS);
    }

    /** Keep the minutes-only walking time current while a session is active. */
    private void walkMinutesTick() {
        if (this.walkSessionStartElapsed == 0L) {
            return;
        }
        updateNotification();
        scheduleWalkMinutesTick();
    }

    private void checkWalkTimeout() {
        if (this.sessionFirstStepElapsed == 0L) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - this.lastStepElapsed >= WALK_IDLE_TIMEOUT_MS) {
            if (this.walkSessionStartElapsed != 0L) {
                this.store.addWalkTime(now - this.walkSessionStartElapsed);
            }
            this.walkSessionStartElapsed = 0L;
            this.sessionFirstStepElapsed = 0L;
            this.sessionSteps = 0;
            this.lastSpeedSampleElapsed = 0L;
            this.stepTimes.clear();
            if (this.walkMinutesTickRunnable != null) {
                this.handler.removeCallbacks(this.walkMinutesTickRunnable);
            }
            updateNotification();
            return;
        }
        scheduleWalkTimeoutCheck();
    }

    // ------------------------------------------------------------------
    // High / low speed warnings: after the live speed stays over the high
    // limit or under the low limit for 5 minutes, a sound warning plays
    // (and repeats every 5 minutes while the breach continues).
    // ------------------------------------------------------------------

    private void scheduleSpeedWarnCheck() {
        if (this.speedWarnRunnable == null) {
            this.speedWarnRunnable = this::checkSpeedWarning;
        }
        this.handler.removeCallbacks(this.speedWarnRunnable);
        this.handler.postDelayed(this.speedWarnRunnable, SPEED_WARN_CHECK_INTERVAL_MS);
    }

    private void checkSpeedWarning() {
        try {
            int highLimit = this.settingsRepository.getSpeedWarnHigh();
            int lowLimit = this.settingsRepository.getSpeedWarnLow();
            long now = SystemClock.elapsedRealtime();
            // Live speed converted to steps per minute.
            float speedSpm = getLiveStepsPerSecond() * 60f;
            boolean walking = this.walkSessionStartElapsed != 0;

            boolean highBreach = highLimit > 0 && speedSpm > highLimit;
            boolean lowBreach = lowLimit > 0 && walking && speedSpm > 0f && speedSpm < lowLimit;

            if (highBreach) {
                if (this.highBreachStartElapsed == 0) {
                    this.highBreachStartElapsed = now;
                    this.highNextWarnElapsed = now + SPEED_WARN_SUSTAIN_MS;
                } else if (now >= this.highNextWarnElapsed) {
                    playWarnTone(true);
                    this.highNextWarnElapsed = now + SPEED_WARN_REPEAT_MS;
                }
            } else {
                this.highBreachStartElapsed = 0;
                this.highNextWarnElapsed = 0;
            }

            if (lowBreach) {
                if (this.lowBreachStartElapsed == 0) {
                    this.lowBreachStartElapsed = now;
                    this.lowNextWarnElapsed = now + SPEED_WARN_SUSTAIN_MS;
                } else if (now >= this.lowNextWarnElapsed) {
                    playWarnTone(false);
                    this.lowNextWarnElapsed = now + SPEED_WARN_REPEAT_MS;
                }
            } else {
                this.lowBreachStartElapsed = 0;
                this.lowNextWarnElapsed = 0;
            }
        } finally {
            scheduleSpeedWarnCheck();
        }
    }

    private void playWarnTone(boolean overHigh) {
        try {
            if (this.toneGenerator == null) {
                this.toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            // Double beep when too fast, a single low beep when too slow.
            this.toneGenerator.startTone(overHigh
                    ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, 1500);
        } catch (RuntimeException ignored) {
            // No audio focus or stream problem: skip this warning.
        }
    }

    private void refreshNotificationThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (now - this.lastNotifRefreshElapsed < MIN_NOTIF_REFRESH_INTERVAL_MS) {
            return;
        }
        this.lastNotifRefreshElapsed = now;
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_steps), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        long stepsToday = this.store.getStepsToday();
        boolean walking = this.walkSessionStartElapsed != 0;
        long currentWalkMs = walking ? SystemClock.elapsedRealtime() - this.walkSessionStartElapsed : 0L;
        long walkTodayMs = this.store.getWalkTimeTodayMs();
        String stepsFormatted = NumberFormat.getIntegerInstance().format(stepsToday);

        RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notif_steps_compact);
        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_steps_expanded);

        // Row 1 of both views: three large numbers — steps | walked | speed.
        // The speed is cached and refreshed at most once a minute.
        String speedText = this.cachedSpeedText;

        compact.setTextViewText(R.id.notifStepsValue, stepsFormatted);
        applyWalkViews(compact, walking, currentWalkMs, walkTodayMs);
        compact.setTextViewText(R.id.notifSpeedMinuteValue, speedText);

        big.setTextViewText(R.id.notifStepsValue, stepsFormatted);
        applyWalkViews(big, walking, currentWalkMs, walkTodayMs);
        big.setTextViewText(R.id.notifSpeedMinuteValue, speedText);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Distinct request code: the player notification uses another one.
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 12, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContent(compact)
                .setCustomBigContentView(big)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build();
    }

    private void applyWalkViews(RemoteViews views, boolean walking, long currentWalkMs, long walkTodayMs) {
        long minutes = walking ? currentWalkMs : walkTodayMs;
        views.setTextViewText(R.id.notifWalkValue, StepStatsStore.formatMinutesOnly(minutes));
        views.setTextViewText(R.id.notifWalkLabel, walking
                ? getString(R.string.notif_label_walking)
                : getString(R.string.notif_label_walk_today));
    }
}
