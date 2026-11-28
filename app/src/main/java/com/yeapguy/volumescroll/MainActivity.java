package com.yeapguy.volumescroll;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.text.DateFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int CALENDAR_MIN_MONTH_OFFSET = -24;
    private static final int CALENDAR_MAX_MONTH_OFFSET = 12;
    private static final int REQUEST_PERMISSIONS_PLAYER = 1001;
    private static final int REQUEST_PERMISSIONS_STEPS = 1002;
    private static final int REQUEST_PICK_MUSIC_FILES = 1004;

    private MaterialSwitch appFilterSwitch;
    private com.google.android.material.card.MaterialCardView cardSteps;
    private LinearLayout calendarContainer;
    private LinearLayout calendarGrid;
    private TextView calendarMonthText;
    private LinearLayout calendarWeekHeader;
    private MaterialSwitch dualKeyToggleSwitch;
    private MaterialButton monthNextButton;
    private MaterialButton monthPrevButton;
    private MaterialButton accessibilityStatusButton;
    private SharedPreferences.OnSharedPreferenceChangeListener settingsChangeListener;
    private SettingsRepository settingsRepository;
    private TextView stepStatsText;
    private MaterialButtonToggleGroup stepTabs;
    private TextView pressesLabelText;
    private Slider pressesSlider;
    private TextView pressesValueText;
    private Slider sliderSpeedWarnHigh;
    private Slider sliderSpeedWarnLow;
    private TextView textSpeedWarnHighValue;
    private TextView textSpeedWarnLowValue;
    private LinearLayout graphTabContainer;
    private LinearLayout speedTabContainer;
    private MinuteGraphView minuteGraph;
    private TextView textMinuteGraphNow;
    private SpeedGraphView speedGraph;
    private TextView textSpeedGraphToday;
    /** Daily average speeds (steps per minute), oldest first, today last. */
    private final float[] graphSpeeds = new float[StepStatsStore.SPEED_GRAPH_DAYS];
    private String graphCacheDay = null;
    private long graphCacheSteps = -1;
    private long graphCacheWalkMs = -1;

    private TextView playerFolderText;
    private TextView playerTrackText;
    private SeekBar playerSeekBar;
    private MaterialButton playerPlayPauseButton;
    private MaterialButton playerPrevButton;
    private MaterialButton playerNextButton;
    private MaterialButton playerFolderPrevButton;
    private MaterialButton playerFolderNextButton;
    private MaterialButton playerRewindButton;
    private MaterialButton playerRandomButton;
    private MaterialButton playerFilesButton;

    private int calendarMonthOffset = 0;
    private long lastCalendarMonthKey = Long.MIN_VALUE;
    private long lastCalendarTodaySteps = -1;
    private boolean seekingByUser = false;
    private String pendingPlayerCommand = null;
    private boolean randomStyleApplied = false;
    private boolean randomStyledOn = false;
    private final Handler statusRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatusRunnable = this::updateServiceStatus;
    private final Runnable uiTickerRunnable = new Runnable() {
        @Override
        public void run() {
            updateStepStats();
            updatePlayerUi();
            statusRefreshHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.settingsRepository = new SettingsRepository(this);
        this.settingsChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                runOnUiThread(MainActivity.this::bindCurrentSettings);
            }
        };

        this.appFilterSwitch = findViewById(R.id.switchAppFilter);
        this.dualKeyToggleSwitch = findViewById(R.id.switchDualKeyToggle);
        this.cardSteps = findViewById(R.id.cardSteps);
        this.stepStatsText = findViewById(R.id.textStepStats);
        this.stepTabs = findViewById(R.id.stepTabs);
        this.calendarContainer = findViewById(R.id.calendarContainer);
        this.calendarMonthText = findViewById(R.id.textCalendarMonth);
        this.calendarWeekHeader = findViewById(R.id.calendarWeekHeader);
        this.calendarGrid = findViewById(R.id.calendarGrid);
        this.monthPrevButton = findViewById(R.id.buttonMonthPrev);
        this.monthNextButton = findViewById(R.id.buttonMonthNext);
        this.pressesSlider = findViewById(R.id.sliderPresses);
        this.pressesLabelText = findViewById(R.id.textPressesLabel);
        this.pressesValueText = findViewById(R.id.textPressesValue);
        this.sliderSpeedWarnHigh = findViewById(R.id.sliderSpeedWarnHigh);
        this.textSpeedWarnHighValue = findViewById(R.id.textSpeedWarnHighValue);
        this.sliderSpeedWarnLow = findViewById(R.id.sliderSpeedWarnLow);
        this.textSpeedWarnLowValue = findViewById(R.id.textSpeedWarnLowValue);
        this.graphTabContainer = findViewById(R.id.graphTabContainer);
        this.speedTabContainer = findViewById(R.id.speedTabContainer);
        this.minuteGraph = findViewById(R.id.minuteGraph);
        this.textMinuteGraphNow = findViewById(R.id.textMinuteGraphNow);
        this.speedGraph = findViewById(R.id.speedGraph);
        this.textSpeedGraphToday = findViewById(R.id.textSpeedGraphToday);
        this.accessibilityStatusButton = findViewById(R.id.buttonAccessibilityStatus);
        this.playerFolderText = findViewById(R.id.textPlayerFolder);
        this.playerTrackText = findViewById(R.id.textPlayerTrack);
        this.playerSeekBar = findViewById(R.id.sliderPlayer);
        this.playerPlayPauseButton = findViewById(R.id.buttonPlayerPlayPause);
        this.playerPrevButton = findViewById(R.id.buttonPlayerPrev);
        this.playerNextButton = findViewById(R.id.buttonPlayerNext);
        this.playerFolderPrevButton = findViewById(R.id.buttonPlayerFolderPrev);
        this.playerFolderNextButton = findViewById(R.id.buttonPlayerFolderNext);
        this.playerRewindButton = findViewById(R.id.buttonPlayerRewind);
        this.playerRandomButton = findViewById(R.id.buttonPlayerRandom);
        this.playerFilesButton = findViewById(R.id.buttonPlayerFiles);
        MaterialButton selectAppsButton = findViewById(R.id.buttonSelectApps);

        this.pressesSlider.setLabelFormatter(new LabelFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.valueOf(Math.round(value));
            }
        });

        bindCurrentSettings();

        this.appFilterSwitch.setOnCheckedChangeListener(this::onAppFilterChecked);
        this.dualKeyToggleSwitch.setOnCheckedChangeListener(this::onDualKeyChecked);

        this.stepTabs.addOnButtonCheckedListener(
                (MaterialButtonToggleGroup group, int checkedId, boolean isChecked) -> {
                    if (!isChecked) {
                        return;
                    }
                    int tab = checkedId == R.id.buttonTabCalendar ? SettingsRepository.TAB_CALENDAR
                            : checkedId == R.id.buttonTabRolling ? SettingsRepository.TAB_ROLLING
                            : checkedId == R.id.buttonTabGraph ? SettingsRepository.TAB_GRAPH
                            : SettingsRepository.TAB_SPEED;
                    if (tab != settingsRepository.getStepStatsTab()) {
                        settingsRepository.setStepStatsTab(tab);
                    }
                    updateStepStats();
                });
        int initialTab = settingsRepository.getStepStatsTab();
        this.stepTabs.check(initialTab == SettingsRepository.TAB_ROLLING ? R.id.buttonTabRolling
                : initialTab == SettingsRepository.TAB_GRAPH ? R.id.buttonTabGraph
                : initialTab == SettingsRepository.TAB_SPEED ? R.id.buttonTabSpeed
                : R.id.buttonTabCalendar);

        this.monthPrevButton.setOnClickListener(v -> {
            calendarMonthOffset--;
            updateStepStats();
        });
        this.monthNextButton.setOnClickListener(v -> {
            if (calendarMonthOffset < CALENDAR_MAX_MONTH_OFFSET) {
                calendarMonthOffset++;
                updateStepStats();
            }
        });

        attachCalendarSwipe();

        this.pressesSlider.addOnChangeListener((Slider slider, float value, boolean fromUser) -> {
            if (!fromUser) {
                return;
            }
            int presses = Math.round(value);
            pressesSlider.setValue(presses);
            settingsRepository.setPressesPerPage(presses);
            pressesValueText.setText(String.valueOf(presses));
        });

        LabelFormatter speedWarnFormatter = value -> value <= 0f
                ? getString(R.string.speed_warn_off) : String.valueOf(Math.round(value));
        this.sliderSpeedWarnHigh.setLabelFormatter(speedWarnFormatter);
        this.sliderSpeedWarnLow.setLabelFormatter(speedWarnFormatter);
        this.sliderSpeedWarnHigh.addOnChangeListener((Slider slider, float value, boolean fromUser) -> {
            if (!fromUser) {
                return;
            }
            int limit = Math.round(value);
            settingsRepository.setSpeedWarnHigh(limit);
            textSpeedWarnHighValue.setText(formatWarnValue(limit));
        });
        this.sliderSpeedWarnLow.addOnChangeListener((Slider slider, float value, boolean fromUser) -> {
            if (!fromUser) {
                return;
            }
            int limit = Math.round(value);
            settingsRepository.setSpeedWarnLow(limit);
            textSpeedWarnLowValue.setText(formatWarnValue(limit));
        });

        this.playerPlayPauseButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_TOGGLE));
        this.playerPrevButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_PREV));
        this.playerNextButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_NEXT));
        this.playerFolderPrevButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_PREV_FOLDER));
        this.playerFolderNextButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_NEXT_FOLDER));
        this.playerRandomButton.setOnClickListener(v -> sendPlayerCommand(FolderPlayerService.ACTION_TOGGLE_RANDOM));
        this.playerFilesButton.setOnClickListener(v -> openSystemFilePicker());
        attachTrackSwipeSeek();
        attachLoopSecondsSwipe();

        this.playerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seekingByUser = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seekingByUser = false;
                sendSeekTo(bar.getProgress());
            }
        });

        selectAppsButton.setOnClickListener(v -> showAppSelectionDialog());
        accessibilityStatusButton.setOnClickListener(v -> startActivity(
                new Intent("android.settings.ACCESSIBILITY_SETTINGS")));
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindCurrentSettings();
        scheduleStatusRefreshes();
        // Step counting is always on when the device has a pedometer.
        if (StepCounterService.hasPedometer(this)) {
            ensureActivityRecognitionPermission();
            startStepCounting();
        }
        updateStepStats();
        updatePlayerUi();
        statusRefreshHandler.removeCallbacks(uiTickerRunnable);
        statusRefreshHandler.postDelayed(uiTickerRunnable, 1000L);
    }

    @Override
    protected void onStart() {
        super.onStart();
        settingsRepository.registerChangeListener(settingsChangeListener);
        bindCurrentSettings();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opening from a notification must not move the UI to the player.
    }

    @Override
    protected void onStop() {
        super.onStop();
        settingsRepository.unregisterChangeListener(settingsChangeListener);
        statusRefreshHandler.removeCallbacks(refreshStatusRunnable);
        statusRefreshHandler.removeCallbacks(uiTickerRunnable);
    }

    private void scheduleStatusRefreshes() {
        statusRefreshHandler.removeCallbacks(refreshStatusRunnable);
        statusRefreshHandler.postDelayed(refreshStatusRunnable, 150L);
        statusRefreshHandler.postDelayed(refreshStatusRunnable, 400L);
        statusRefreshHandler.postDelayed(refreshStatusRunnable, 900L);
    }

    private void startStepCounting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, StepCounterService.class));
        } else {
            startService(new Intent(this, StepCounterService.class));
        }
    }

    // ------------------------------------------------------------------
    // Settings binding
    // ------------------------------------------------------------------

    public void bindCurrentSettings() {
        boolean appFilterEnabled = settingsRepository.isAppFilterEnabled();
        int selectedAppsCount = settingsRepository.getAllowedAppPackages().size();
        int presses = settingsRepository.getPressesPerPage();

        appFilterSwitch.setChecked(appFilterEnabled);
        dualKeyToggleSwitch.setChecked(settingsRepository.isShortcutToggleEnabled());
        pressesSlider.setValue(presses);
        pressesValueText.setText(String.valueOf(presses));

        int warnHigh = settingsRepository.getSpeedWarnHigh();
        int warnLow = settingsRepository.getSpeedWarnLow();
        // Sliders step by 5: keep the stored value aligned so setValue() is valid.
        sliderSpeedWarnHigh.setValue(Math.round(warnHigh / 5f) * 5f);
        sliderSpeedWarnLow.setValue(Math.round(warnLow / 5f) * 5f);
        textSpeedWarnHighValue.setText(formatWarnValue(warnHigh));
        textSpeedWarnLowValue.setText(formatWarnValue(warnLow));

        boolean serviceEnabled = updateServiceStatus();
        updateSettingsEnabledState(serviceEnabled, appFilterEnabled);
    }

    private static String formatWarnValue(int limit) {
        return limit <= 0 ? "\u2014" : String.valueOf(limit);
    }

    private void updateSettingsEnabledState(boolean serviceEnabled, boolean appFilterEnabled) {
        boolean appFilterToggleEnabled = serviceEnabled;
        boolean appPickerEnabled = appFilterToggleEnabled && appFilterEnabled;
        MaterialButton selectAppsButton = findViewById(R.id.buttonSelectApps);

        dualKeyToggleSwitch.setEnabled(serviceEnabled);
        appFilterSwitch.setEnabled(appFilterToggleEnabled);
        selectAppsButton.setEnabled(appPickerEnabled);
        pressesSlider.setEnabled(serviceEnabled);
        pressesLabelText.setEnabled(serviceEnabled);
        pressesValueText.setEnabled(serviceEnabled);

        float dim = serviceEnabled ? 1.0f : 0.45f;
        float appFilterAlpha = appFilterToggleEnabled ? 1.0f : 0.45f;
        float appPickerAlpha = appPickerEnabled ? 1.0f : 0.45f;

        dualKeyToggleSwitch.setAlpha(dim);
        appFilterSwitch.setAlpha(appFilterAlpha);
        selectAppsButton.setAlpha(appPickerAlpha);
        pressesSlider.setAlpha(dim);
        pressesLabelText.setAlpha(dim);
        pressesValueText.setAlpha(dim);
    }

    // ------------------------------------------------------------------
    // Switch handlers
    // ------------------------------------------------------------------

    private void onAppFilterChecked(CompoundButton buttonView, boolean isChecked) {
        settingsRepository.setAppFilterEnabled(isChecked);
        updateSettingsEnabledState(updateServiceStatus(), isChecked);
    }

    private void onDualKeyChecked(CompoundButton buttonView, boolean isChecked) {
        settingsRepository.setShortcutToggleEnabled(isChecked);
    }

    // ------------------------------------------------------------------
    // Step statistics (with live speed)
    // ------------------------------------------------------------------

    public void updateStepStats() {
        boolean sensorAvailable = StepCounterService.hasPedometer(this);
        if (!sensorAvailable) {
            // No pedometer on this device: hide the whole step section.
            cardSteps.setVisibility(View.GONE);
            return;
        }
        cardSteps.setVisibility(View.VISIBLE);

        int tab = settingsRepository.getStepStatsTab();
        boolean calendarTab = tab == SettingsRepository.TAB_CALENDAR;
        boolean rollingTab = tab == SettingsRepository.TAB_ROLLING;
        boolean graphTab = tab == SettingsRepository.TAB_GRAPH;
        boolean speedTab = tab == SettingsRepository.TAB_SPEED;

        calendarContainer.setVisibility(calendarTab ? View.VISIBLE : View.GONE);
        stepStatsText.setVisibility(rollingTab ? View.VISIBLE : View.GONE);
        graphTabContainer.setVisibility(graphTab ? View.VISIBLE : View.GONE);
        speedTabContainer.setVisibility(speedTab ? View.VISIBLE : View.GONE);

        StepStatsStore store = new StepStatsStore(this);
        if (calendarTab) {
            rebuildCalendar(store, store.getStepsToday(), false);
        }
        if (rollingTab) {
            stepStatsText.setText(buildSpeedText(store, buildRollingStats(store)));
        }
        if (graphTab) {
            updateMinuteGraph(store);
        }
        if (speedTab) {
            updateSpeedGraph(store);
        }
    }

    /**
     * Rolling tab: for every period (week, month, 4 months, year, all) —
     * the average steps per day, then "walked today" as before.
     */
    private String buildRollingStats(StepStatsStore store) {
        NumberFormat numberFormat = NumberFormat.getIntegerInstance();
        String unit = getString(R.string.stat_speed_unit);
        StringBuilder b = new StringBuilder();
        appendPeriodRow(b, getString(R.string.stat_last_7_days), store.getRollingPeriod(7), unit, numberFormat);
        appendPeriodRow(b, getString(R.string.stat_last_30_days), store.getRollingPeriod(30), unit, numberFormat);
        appendPeriodRow(b, getString(R.string.stat_last_4_months), store.getRollingPeriod(120), unit, numberFormat);
        appendPeriodRow(b, getString(R.string.stat_last_year), store.getRollingPeriod(365), unit, numberFormat);
        appendPeriodRow(b, getString(R.string.stat_all_time), store.getAllTimePeriod(), unit, numberFormat);
        b.append(getString(R.string.stat_walk_today)).append(": ")
                .append(StepStatsStore.formatDuration(store.getWalkTimeTodayMs()));
        return b.toString();
    }

    private static void appendPeriodRow(StringBuilder b, String label,
                                        StepStatsStore.PeriodStats stats, String unit,
                                        NumberFormat numberFormat) {
        long stepsPerDay = stats.days > 0 ? stats.steps / stats.days : 0L;
        b.append(label).append(": ").append(numberFormat.format(stepsPerDay)).append('\n');
    }

    /**
     * Minute graph: one vertical line per minute of the day, the height of
     * the line is that minute's speed (steps per minute).
     */
    private void updateMinuteGraph(StepStatsStore store) {
        int[] minutes = store.getMinuteStepsToday();
        Calendar now = Calendar.getInstance();
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        this.minuteGraph.setData(minutes, nowMinute, this.settingsRepository.getSpeedWarnLow());
        int lastMinuteSteps = minutes[Math.max(0, Math.min(nowMinute - 1, minutes.length - 1))];
        this.textMinuteGraphNow.setText(getString(R.string.minute_graph_now_prefix) + " "
                + (lastMinuteSteps > 0
                        ? lastMinuteSteps + " " + getString(R.string.stat_speed_unit)
                        : "\u2014"));
    }

    /**
     * Stats text followed by the small records line and the current speed in
     * a large accent font. The large speed is the live walking speed; when
     * idle, today's average is shown instead.
     */
    private CharSequence buildSpeedText(StepStatsStore store, String statsText) {
        String unit = getString(R.string.stat_speed_unit);
        int bestToday = Math.round(store.getBestSpeedToday() * 60f);
        int record = Math.round(store.getBestSpeedAllTime() * 60f);
        String recordsLine = getString(R.string.stat_speed_best_today) + " "
                + (bestToday > 0 ? String.valueOf(bestToday) : "\u2014")
                + " \u00B7 " + getString(R.string.stat_speed_record) + " "
                + (record > 0 ? String.valueOf(record) : "\u2014")
                + " (" + unit + ")\n";

        float live = StepCounterService.getLiveStepsPerSecond();
        float avgToday = store.getAvgSpeedToday();
        String bigValue;
        if (live > 0f) {
            bigValue = String.valueOf(Math.round(live * 60f));
        } else if (avgToday > 0f) {
            bigValue = String.valueOf(Math.round(avgToday));
        } else {
            bigValue = "\u2014";
        }
        String bigLine = "\u2014".equals(bigValue) ? bigValue : bigValue + " " + unit;

        String baseText = statsText + "\n" + recordsLine;
        SpannableString rich = new SpannableString(baseText + bigLine);
        int bigStart = baseText.length();
        rich.setSpan(new RelativeSizeSpan(1.9f), bigStart, rich.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rich.setSpan(new StyleSpan(Typeface.BOLD), bigStart, rich.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rich.setSpan(new ForegroundColorSpan(MaterialColors.getColor(stepStatsText,
                com.google.android.material.R.attr.colorPrimary)), bigStart, rich.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return rich;
    }

    /**
     * Feeds the speed graph (this day + the last year, steps per minute).
     * Rebuilding is throttled: data only changes when today's steps or walk
     * time change, or the day rolls over.
     */
    private void updateSpeedGraph(StepStatsStore store) {
        long stepsToday = store.getStepsToday();
        long walkMs = store.getWalkTimeTodayMs();
        String today = store.getTodayKey();
        if (today.equals(this.graphCacheDay)
                && stepsToday == this.graphCacheSteps
                && walkMs == this.graphCacheWalkMs) {
            return;
        }
        this.graphCacheDay = today;
        this.graphCacheSteps = stepsToday;
        this.graphCacheWalkMs = walkMs;
        store.fillDailyAvgSpeeds(this.graphSpeeds);
        this.speedGraph.setData(this.graphSpeeds, this.graphSpeeds.length - 1,
                this.settingsRepository.getSpeedWarnLow());
        float avgToday = this.graphSpeeds[this.graphSpeeds.length - 1];
        this.textSpeedGraphToday.setText(getString(R.string.speed_graph_today_prefix) + " "
                + (avgToday > 0f ? String.valueOf(Math.round(avgToday)) : "\u2014"));
    }

    private void rebuildCalendar(StepStatsStore store, long todaySteps, boolean force) {
        monthPrevButton.setEnabled(calendarMonthOffset > CALENDAR_MIN_MONTH_OFFSET);
        monthNextButton.setEnabled(calendarMonthOffset < CALENDAR_MAX_MONTH_OFFSET);

        Calendar shown = Calendar.getInstance();
        zeroCalendar(shown);
        shown.set(Calendar.DAY_OF_MONTH, 1);
        shown.add(Calendar.MONTH, calendarMonthOffset);
        long monthKey = (shown.get(Calendar.YEAR) * 12) + shown.get(Calendar.MONTH);
        if (!force && monthKey == lastCalendarMonthKey && todaySteps == lastCalendarTodaySteps) {
            return;
        }
        lastCalendarMonthKey = monthKey;
        lastCalendarTodaySteps = todaySteps;

        calendarMonthText.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                .format(shown.getTime()));
        ensureWeekdayHeader();
        calendarGrid.removeAllViews();

        SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar dayCursor = (Calendar) shown.clone();
        Calendar now = Calendar.getInstance();
        int todayDayOfMonth = now.get(Calendar.DAY_OF_MONTH);
        boolean showingCurrentMonth = calendarMonthOffset == 0;

        int colorNormal = MaterialColors.getColor(calendarGrid, com.google.android.material.R.attr.colorOnSurface);
        int colorDim = MaterialColors.getColor(calendarGrid, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int colorAccent = MaterialColors.getColor(calendarGrid, com.google.android.material.R.attr.colorPrimary);
        int colorGreen = 0xFF2E7D32;
        int colorGreenBg = 0x332E7D32;

        int leadingEmptyCells = (shown.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int daysInMonth = shown.getActualMaximum(Calendar.DAY_OF_MONTH);
        LinearLayout row = null;
        int cellIndex = 0;

        for (int slot = 0; slot < leadingEmptyCells + daysInMonth; slot++) {
            if (cellIndex % 7 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                calendarGrid.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            int dayOfMonth = slot - leadingEmptyCells;
            TextView cell = new TextView(this);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(2), 0, dp(2));
            cell.setMinHeight(dp(26));
            cell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (dayOfMonth >= 0) {
                dayCursor.set(Calendar.DAY_OF_MONTH, dayOfMonth + 1);
                long steps = store.getStepsForDay(keyFormat.format(dayCursor.getTime()));
                boolean isToday = showingCurrentMonth && dayOfMonth + 1 == todayDayOfMonth;
                boolean isMarked = isGreenMarkDay(dayCursor);
                String dayLabel = String.valueOf(dayOfMonth + 1);
                String stepsLabel = steps > 0 ? formatStepsCompact(steps) : "";
                if (stepsLabel.isEmpty()) {
                    cell.setText(dayLabel + "\n");
                } else {
                    SpannableString rich = new SpannableString(dayLabel + "\n" + stepsLabel);
                    rich.setSpan(new RelativeSizeSpan(0.78f), dayLabel.length() + 1, rich.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    cell.setText(rich);
                }
                if (isMarked) {
                    // Every 4th day from 26.11.2026: highlighted green.
                    cell.setBackgroundColor(colorGreenBg);
                    cell.setTypeface(Typeface.DEFAULT_BOLD);
                    cell.setTextColor(colorGreen);
                } else if (isToday) {
                    cell.setTypeface(Typeface.DEFAULT_BOLD);
                    cell.setTextColor(colorAccent);
                } else if (steps > 0) {
                    cell.setTextColor(colorNormal);
                } else {
                    cell.setTextColor(colorDim);
                }
            }
            cellIndex++;
        }
        while (row != null && cellIndex % 7 != 0) {
            row.addView(new TextView(this), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            cellIndex++;
        }
    }

    private void ensureWeekdayHeader() {
        if (calendarWeekHeader.getChildCount() > 0) {
            return;
        }
        String[] shortWeekdays = DateFormatSymbols.getInstance(Locale.getDefault()).getShortWeekdays();
        int[] mondayFirstOrder = {2, 3, 4, 5, 6, 7, 1};
        int colorDim = MaterialColors.getColor(calendarWeekHeader,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int dayOfWeek : mondayFirstOrder) {
            TextView label = new TextView(this);
            label.setText(shortWeekdays[dayOfWeek]);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f);
            label.setTextColor(colorDim);
            calendarWeekHeader.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static String formatStepsCompact(long steps) {
        if (steps >= 1000000) {
            return (steps / 1000000) + "M";
        }
        if (steps >= 100000) {
            return (steps / 1000) + "k";
        }
        if (steps >= 1000) {
            long tenths = steps / 100;
            return (tenths / 10) + "." + (tenths % 10) + "k";
        }
        return String.valueOf(steps);
    }

    private static void zeroCalendar(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    /**
     * Green highlight: every 4th day counting from 26.11.2026 (inclusive),
     * i.e. 26.11, 30.11, 04.12, 08.12, ... Compared as calendar dates (not
     * timestamps) so timezone/DST shifts cannot offset the sequence.
     */
    private static boolean isGreenMarkDay(Calendar day) {
        long dayNumber = julianDayNumber(day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
        long startNumber = julianDayNumber(2026, 11, 26);
        long diff = dayNumber - startNumber;
        return diff >= 0 && diff % 4 == 0;
    }

    /** Gregorian Julian day number — date-only, no timezone involvement. */
    private static long julianDayNumber(int year, int month, int dayOfMonth) {
        int a = (14 - month) / 12;
        int y = year + 4800 - a;
        int m = month + 12 * a - 3;
        return dayOfMonth + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Horizontal swipe on the calendar switches months: swipe left = next
     * month, swipe right = previous month (same bounds as the arrow buttons).
     * The ScrollView parent would normally steal a slightly diagonal drag, so
     * the parent's touch interception is blocked as soon as the gesture is
     * identified as horizontal.
     */
    private void attachCalendarSwipe() {
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] decided = {false};
        final boolean[] horizontal = {false};
        final float touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        calendarContainer.setClickable(true);
        calendarContainer.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    decided[0] = false;
                    horizontal[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!decided[0]) {
                        float dx = event.getX() - downX[0];
                        float dy = event.getY() - downY[0];
                        if (Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy)) {
                            decided[0] = true;
                            horizontal[0] = true;
                            // Keep the outer ScrollView out of this gesture.
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        } else if (Math.abs(dy) > touchSlopPx && Math.abs(dy) > Math.abs(dx)) {
                            decided[0] = true;
                            horizontal[0] = false;
                        }
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    boolean wasHorizontal = decided[0] && horizontal[0];
                    decided[0] = false;
                    if (wasHorizontal) {
                        float dx = event.getX() - downX[0];
                        if (Math.abs(dx) > dp(56)) {
                            if (dx < 0 && calendarMonthOffset < CALENDAR_MAX_MONTH_OFFSET) {
                                calendarMonthOffset++;
                            } else if (dx > 0 && calendarMonthOffset > CALENDAR_MIN_MONTH_OFFSET) {
                                calendarMonthOffset--;
                            } else {
                                return true;
                            }
                            updateStepStats();
                        }
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    // ------------------------------------------------------------------
    // Folder player UI
    // ------------------------------------------------------------------

    private void updatePlayerUi() {
        FolderPlayerService.Snapshot snapshot = FolderPlayerService.CURRENT;
        boolean running = FolderPlayerService.isRunning();

        if (!running) {
            // No playback yet: show the remembered track so it is obvious
            // what Play will resume. No library scan happens until Play.
            String savedFolder = settingsRepository.getPlayerLastFolder();
            String savedTrack = settingsRepository.getPlayerLastTrack();
            if (savedFolder != null && savedTrack != null) {
                playerFolderText.setText(savedFolder);
                playerTrackText.setText(savedTrack + " \u00B7 "
                        + getString(R.string.player_status_saved_position));
            } else {
                playerFolderText.setText(R.string.player_status_idle);
                playerTrackText.setText("");
            }
            playerPlayPauseButton.setText(R.string.player_btn_play);
            applyRandomButtonStyle(settingsRepository.getPlayerRandom());
            playerRewindButton.setText(getString(R.string.player_btn_rewind_fmt,
                    settingsRepository.getPlayerLoopSeconds()));
            playerRewindButton.setEnabled(false);
            playerFilesButton.setEnabled(true);
            playerSeekBar.setEnabled(false);
            playerSeekBar.setProgress(0);
            return;
        }

        playerRewindButton.setEnabled(true);
        playerFilesButton.setEnabled(true);
        playerRewindButton.setText(getString(snapshot.loopActive
                ? R.string.player_btn_loop_on_fmt : R.string.player_btn_rewind_fmt,
                snapshot.loopSeconds));

        // Seekbar follows playback unless the user is dragging it right now.
        if (!seekingByUser) {
            if (snapshot.ready && snapshot.durationMs > 0) {
                playerSeekBar.setEnabled(true);
                playerSeekBar.setMax(snapshot.durationMs);
                playerSeekBar.setProgress(Math.min(snapshot.positionMs, snapshot.durationMs));
            } else {
                playerSeekBar.setEnabled(false);
                playerSeekBar.setProgress(0);
            }
        }

        if (snapshot.scanning) {
            playerFolderText.setText(R.string.player_status_scanning);
            playerTrackText.setText("");
        } else if (snapshot.error != null) {
            playerFolderText.setText(snapshot.error);
            playerTrackText.setText("");
        } else if (snapshot.ready) {
            playerFolderText.setText(snapshot.folderName + " \u2014 "
                    + (snapshot.trackIndex + 1) + "/" + snapshot.trackCount);
            playerTrackText.setText(snapshot.fileName + " \u00B7 "
                    + formatPlaybackTime(snapshot.positionMs) + " / "
                    + formatPlaybackTime(snapshot.durationMs));
        } else {
            playerFolderText.setText(R.string.player_status_permission);
            playerTrackText.setText("");
        }

        playerPlayPauseButton.setText(snapshot.playing
                ? R.string.player_btn_pause : R.string.player_btn_play);
        applyRandomButtonStyle(snapshot.random);
    }

    /**
     * Random is always labeled just "Random"; white = off, blue = on.
     */
    private void applyRandomButtonStyle(boolean on) {
        if (randomStyleApplied && randomStyledOn == on) {
            return;
        }
        randomStyleApplied = true;
        randomStyledOn = on;
        playerRandomButton.setText(R.string.player_random);
        if (on) {
            playerRandomButton.setBackgroundTintList(ColorStateList.valueOf(0xFF1976D2));
            playerRandomButton.setTextColor(Color.WHITE);
        } else {
            playerRandomButton.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            playerRandomButton.setTextColor(0xFF202020);
        }
    }

    private static String formatPlaybackTime(int ms) {
        int totalSeconds = Math.max(0, ms) / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void sendPlayerCommand(String action) {
        if (!hasStoragePermission()) {
            pendingPlayerCommand = action;
            requestPlayerPermissions();
            return;
        }
        deliverPlayerCommand(action);
    }

    private void deliverPlayerCommand(String action) {
        Intent intent = new Intent(this, FolderPlayerService.class).setAction(action);
        if (FolderPlayerService.isRunning()) {
            startService(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPlayerPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS_PLAYER);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_PLAYER) {
            boolean granted = false;
            for (int i = 0; i < permissions.length; i++) {
                if ((Manifest.permission.READ_EXTERNAL_STORAGE.equals(permissions[i])
                        || Manifest.permission.READ_MEDIA_AUDIO.equals(permissions[i]))
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            if (granted && pendingPlayerCommand != null) {
                String command = pendingPlayerCommand;
                pendingPlayerCommand = null;
                deliverPlayerCommand(command);
            } else if (!granted) {
                Toast.makeText(this, R.string.player_status_permission, Toast.LENGTH_SHORT).show();
                pendingPlayerCommand = null;
            }
        }
        // REQUEST_PERMISSIONS_STEPS needs no follow-up: the sensor simply
        // starts delivering events once the permission is granted (API 29+).
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_MUSIC_FILES && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri item = clip.getItemAt(i).getUri();
                    if (item != null) {
                        uris.add(item);
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, R.string.player_status_no_music, Toast.LENGTH_SHORT).show();
                return;
            }
            for (Uri uri : uris) {
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (RuntimeException ignored) {
                    // Grant may already be held; the player will tell if not.
                }
            }
            // Preferred behavior: the folder of the picked file becomes the
            // playlist and playback starts at the picked file. Only local
            // documents resolve to a real path; cloud files fall back to the
            // picked-files-only library below.
            File picked = MusicLibrary.resolveLocalFile(this, uris.get(0));
            if (picked != null && picked.isFile()) {
                File dir = picked.getParentFile();
                if (dir != null && dir.isDirectory()) {
                    settingsRepository.setPlayerFolderDir(dir.getAbsolutePath());
                    settingsRepository.setPlayerStartFile(picked.getAbsolutePath());
                    settingsRepository.setPlayerFileUris(null);
                    settingsRepository.setPlayerTreeUri(null);
                    deliverPlayerCommand(FolderPlayerService.ACTION_RELOAD);
                    return;
                }
            }
            // Fallback: remember just the picked files.
            List<String> stored = new ArrayList<>();
            for (Uri uri : uris) {
                stored.add(uri.toString());
            }
            settingsRepository.setPlayerFileUris(stored);
            settingsRepository.setPlayerFolderDir(null);
            settingsRepository.setPlayerStartFile(null);
            settingsRepository.setPlayerTreeUri(null);
            deliverPlayerCommand(FolderPlayerService.ACTION_RELOAD);
        }
    }

    /**
     * Opens the system file selector: one or many audio/video files (video is
     * played audio-only). The folder of the first picked file becomes the
     * playlist and playback starts at that file.
     */
    private void openSystemFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQUEST_PICK_MUSIC_FILES);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.player_status_no_music, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Swipe the track name left/right to rewind / fast-forward: roughly one
     * second of playback per 40dp of horizontal drag, applied live. Like the
     * calendar swipe, the outer ScrollView is blocked once the gesture turns
     * out to be horizontal (otherwise a slightly diagonal start cancels it).
     */
    private void attachTrackSwipeSeek() {
        final int[] accumulatedPx = {0};
        final float[] startX = {0f};
        final float[] startY = {0f};
        final boolean[] dragging = {false};
        final float touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        playerTrackText.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startX[0] = event.getX();
                    startY[0] = event.getY();
                    accumulatedPx[0] = 0;
                    dragging[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - startX[0];
                    if (!dragging[0]) {
                        float dy = event.getY() - startY[0];
                        if (Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy)) {
                            dragging[0] = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        } else {
                            return true;
                        }
                    }
                    accumulatedPx[0] = Math.round(dx);
                    int pxPerSecond = Math.max(20, dp(40));
                    int seconds = accumulatedPx[0] / pxPerSecond;
                    if (seconds != 0) {
                        // Consume the whole seconds and re-anchor the gesture.
                        startX[0] += seconds * pxPerSecond;
                        accumulatedPx[0] -= seconds * pxPerSecond;
                        seekIntent(seconds * 1000);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging[0] = false;
                    return false;
                default:
                    return false;
            }
        });
    }

    /**
     * The \u2212N s button: tap toggles the loop, horizontal swipe changes the
     * number of seconds to loop (right = more, left = less, 1 s per 24 dp,
     * within the 1..30 seconds range). The outer ScrollView is blocked once
     * the gesture is identified as horizontal.
     */
    private void attachLoopSecondsSwipe() {
        final float[] downX = {0f};
        final float[] downY = {0f};
        final float[] anchorX = {0f};
        final boolean[] decided = {false};
        final boolean[] horizontal = {false};
        final boolean[] stepped = {false};
        final float touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        final int stepPx = Math.max(18, dp(24));
        playerRewindButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    anchorX[0] = event.getX();
                    decided[0] = false;
                    horizontal[0] = false;
                    stepped[0] = false;
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - downX[0];
                    float dy = event.getY() - downY[0];
                    if (!decided[0]) {
                        if (Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy)) {
                            decided[0] = true;
                            horizontal[0] = true;
                            // Keep the outer ScrollView out of this gesture.
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        } else if (Math.abs(dy) > touchSlopPx) {
                            decided[0] = true;
                            horizontal[0] = false;
                        }
                    }
                    if (decided[0] && horizontal[0]) {
                        int steps = Math.round((event.getX() - anchorX[0]) / stepPx);
                        if (steps != 0) {
                            anchorX[0] += steps * stepPx;
                            stepped[0] = true;
                            changeLoopSeconds(steps);
                        }
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    boolean gesture = decided[0];
                    decided[0] = false;
                    v.setPressed(false);
                    if (gesture) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (stepped[0] || gesture
                            || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                        stepped[0] = false;
                        return true; // swipe or drag consumed; no click
                    }
                    // Plain tap: toggle the loop on/off.
                    sendPlayerCommand(FolderPlayerService.ACTION_REWIND_5S);
                    return true;
                default:
                    return false;
            }
        });
    }

    /** Adds delta seconds to the loop window (clamped 1..30) and tells the player. */
    private void changeLoopSeconds(int deltaSeconds) {
        int seconds = SettingsRepository.clampLoopSeconds(
                settingsRepository.getPlayerLoopSeconds() + deltaSeconds);
        settingsRepository.setPlayerLoopSeconds(seconds);
        playerRewindButton.setText(getString(R.string.player_btn_rewind_fmt, seconds));
        if (!FolderPlayerService.isRunning()) {
            return; // the service picks the value up from prefs when it starts
        }
        if (!hasStoragePermission()) {
            return;
        }
        Intent intent = new Intent(this, FolderPlayerService.class)
                .setAction(FolderPlayerService.ACTION_SET_LOOP_SECONDS)
                .putExtra(FolderPlayerService.EXTRA_LOOP_SECONDS, seconds);
        startService(intent);
    }

    /** Sends a relative-seek command (ms, positive = forward) to the player. */
    private void seekIntent(int ms) {
        if (!hasStoragePermission()) {
            return;
        }
        Intent intent = new Intent(this, FolderPlayerService.class)
                .setAction(FolderPlayerService.ACTION_SEEK)
                .putExtra(FolderPlayerService.EXTRA_SEEK_MS, ms);
        if (FolderPlayerService.isRunning()) {
            startService(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /** Sends an absolute-seek command (ms from track start) to the player. */
    private void sendSeekTo(int ms) {
        if (!hasStoragePermission()) {
            return;
        }
        Intent intent = new Intent(this, FolderPlayerService.class)
                .setAction(FolderPlayerService.ACTION_SEEK_TO)
                .putExtra(FolderPlayerService.EXTRA_SEEK_MS, Math.max(0, ms));
        if (FolderPlayerService.isRunning()) {
            startService(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ------------------------------------------------------------------
    // App selection dialog
    // ------------------------------------------------------------------

    private boolean updateServiceStatus() {
        boolean enabled = AccessibilityStatusChecker.isServiceEnabled(this, VolumeScrollAccessibilityService.class);
        accessibilityStatusButton.setText(enabled
                ? R.string.status_accessibility_on : R.string.status_accessibility_off);
        int colorAttr = enabled
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorError;
        accessibilityStatusButton.setTextColor(MaterialColors.getColor(accessibilityStatusButton, colorAttr));
        return enabled;
    }

    /** Asks for the activity-recognition permission once (API 29+ pedometer). */
    private void ensureActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                    REQUEST_PERMISSIONS_STEPS);
        }
    }

    private void showAppSelectionDialog() {
        final List<AppEntry> launchableApps = getLaunchableApps();
        Set<String> currentSelectedPackages = settingsRepository.getAllowedAppPackages();
        CharSequence[] labels = new CharSequence[launchableApps.size()];
        final boolean[] checked = new boolean[launchableApps.size()];
        for (int i = 0; i < launchableApps.size(); i++) {
            AppEntry app = launchableApps.get(i);
            labels[i] = app.label;
            checked[i] = currentSelectedPackages.contains(app.packageName);
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_select_apps_title)
                .setMultiChoiceItems(labels, checked, (DialogInterface dialogInterface, int which, boolean isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton(R.string.action_save, (DialogInterface dialogInterface, int which) -> {
                    Set<String> selectedPackages = new HashSet<>();
                    for (int i = 0; i < launchableApps.size(); i++) {
                        if (checked[i]) {
                            selectedPackages.add(launchableApps.get(i).packageName);
                        }
                    }
                    settingsRepository.setAllowedAppPackages(selectedPackages);
                })
                .setNeutralButton(R.string.action_actions, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    PopupMenu menu = new PopupMenu(MainActivity.this, v);
                    Menu popupMenu = menu.getMenu();
                    popupMenu.add(0, 1, 0, R.string.action_select_all);
                    popupMenu.add(0, 2, 1, R.string.action_invert_selection);
                    popupMenu.add(0, 3, 2, R.string.action_clear);
                    menu.setOnMenuItemClickListener(item -> {
                        int itemId = item.getItemId();
                        if (itemId == 1) {
                            setCheckedStates(dialog, checked, true);
                            return true;
                        }
                        if (itemId == 2) {
                            invertCheckedStates(dialog, checked);
                            return true;
                        }
                        if (itemId == 3) {
                            setCheckedStates(dialog, checked, false);
                            return true;
                        }
                        return false;
                    });
                    menu.show();
                }));
        dialog.show();
    }

    private void setCheckedStates(AlertDialog dialog, boolean[] checked, boolean checkedState) {
        ListView listView = dialog.getListView();
        for (int i = 0; i < checked.length; i++) {
            checked[i] = checkedState;
            if (listView != null) {
                listView.setItemChecked(i, checkedState);
            }
        }
    }

    private void invertCheckedStates(AlertDialog dialog, boolean[] checked) {
        ListView listView = dialog.getListView();
        for (int i = 0; i < checked.length; i++) {
            checked[i] = !checked[i];
            if (listView != null) {
                listView.setItemChecked(i, checked[i]);
            }
        }
    }

    private List<AppEntry> getLaunchableApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName != null && !seenPackages.contains(packageName)) {
                seenPackages.add(packageName);
                CharSequence label = resolveInfo.loadLabel(getPackageManager());
                String labelText = label == null ? packageName : label.toString();
                apps.add(new AppEntry(labelText, packageName));
            }
        }
        apps.sort(Comparator.comparing(app -> app.label.toLowerCase(Locale.getDefault())));
        return apps;
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
