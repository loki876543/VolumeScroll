package com.yeapguy.volumescroll;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Access layer for the app's SharedPreferences. */
public final class SettingsRepository {
    /** Default: 4 volume presses make one full screen scroll (25% per press). */
    public static final int DEFAULT_PRESSES_PER_PAGE = 4;
    public static final int MIN_PRESSES_PER_PAGE = 1;
    public static final int MAX_PRESSES_PER_PAGE = 10;

    /** Default loop window of the \u2212N s button. */
    public static final int DEFAULT_LOOP_SECONDS = 5;
    public static final int MIN_LOOP_SECONDS = 1;
    public static final int MAX_LOOP_SECONDS = 30;

    /** Speed warning limits, steps per minute. */
    public static final int MIN_SPEED_WARN = 50;
    public static final int MAX_SPEED_WARN = 200;
    public static final int DEFAULT_SPEED_WARN_HIGH = 110;
    public static final int DEFAULT_SPEED_WARN_LOW = 90;

    /** Firefox scrolls by default; the app itself (Loki) is added at runtime. */
    public static final String DEFAULT_SCROLL_PACKAGE_FIREFOX = "org.mozilla.firefox";

    private static final String KEY_ALLOWED_APP_PACKAGES = "allowed_app_packages";
    private static final String KEY_APP_FILTER_ENABLED = "app_filter_enabled";
    private static final String KEY_SCROLL_DEFAULTS_V2 = "scroll_defaults_v2_applied";
    private static final String KEY_FEATURE_ENABLED = "feature_enabled";
    private static final String KEY_INVERT_DIRECTION = "invert_direction";
    private static final String KEY_PRESSES_PER_PAGE = "presses_per_page";
    private static final String KEY_SHORTCUT_TOGGLE_ENABLED = "shortcut_toggle_enabled";
    private static final String KEY_STEP_STATS_CALENDAR_MODE = "step_stats_calendar_mode";
    private static final String KEY_STEP_STATS_TAB = "step_stats_tab";
    private static final String KEY_PLAYER_TREE_URI = "player_tree_uri";
    private static final String KEY_PLAYER_FOLDER_DIR = "player_folder_dir";
    private static final String KEY_PLAYER_START_FILE = "player_start_file";
    private static final String KEY_PLAYER_LAST_FOLDER = "player_last_folder";
    private static final String KEY_PLAYER_LAST_TRACK = "player_last_track";
    private static final String KEY_PLAYER_LAST_POSITION_MS = "player_last_position_ms";
    private static final String KEY_PLAYER_RANDOM = "player_random";
    private static final String KEY_PLAYER_LOOP_SECONDS = "player_loop_seconds";
    private static final String KEY_PLAYER_FILE_URIS = "player_file_uris";
    private static final String KEY_SPEED_WARN_HIGH = "speed_warn_high_spm";
    private static final String KEY_SPEED_WARN_LOW = "speed_warn_low_spm";
    private static final String LEGACY_KEY_SCROLL_AMOUNT = "scroll_amount";
    private static final String LEGACY_KEY_PAGE_SCROLL_MODE = "page_scroll_mode";
    private static final String PREFS_NAME = "volume_scroll_prefs";

    private final Context appContext;
    private final SharedPreferences sharedPreferences;

    public SettingsRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.sharedPreferences = appContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isFeatureEnabled() {
        return this.sharedPreferences.getBoolean(KEY_FEATURE_ENABLED, true);
    }

    public void setFeatureEnabled(boolean enabled) {
        this.sharedPreferences.edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply();
    }

    public boolean isInvertDirectionEnabled() {
        return this.sharedPreferences.getBoolean(KEY_INVERT_DIRECTION, false);
    }

    public void setInvertDirectionEnabled(boolean enabled) {
        this.sharedPreferences.edit().putBoolean(KEY_INVERT_DIRECTION, enabled).apply();
    }

    /**
     * How many volume presses make up one full screen of scrolling.
     * Each press scrolls exactly 1/N of the screen instantly.
     */
    public int getPressesPerPage() {
        int saved = this.sharedPreferences.getInt(KEY_PRESSES_PER_PAGE, Integer.MIN_VALUE);
        if (saved != Integer.MIN_VALUE) {
            return clampPresses(saved);
        }
        // First launch after upgrading from a percentage-based build: the old
        // percentage never mapped to a real on-screen distance, so start from
        // the default the user asked for (4 presses per screen) and drop the
        // legacy keys.
        if (this.sharedPreferences.contains(LEGACY_KEY_SCROLL_AMOUNT)
                || this.sharedPreferences.contains(LEGACY_KEY_PAGE_SCROLL_MODE)) {
            this.sharedPreferences.edit()
                    .remove(LEGACY_KEY_SCROLL_AMOUNT)
                    .remove(LEGACY_KEY_PAGE_SCROLL_MODE)
                    .putInt(KEY_PRESSES_PER_PAGE, DEFAULT_PRESSES_PER_PAGE)
                    .apply();
        }
        return DEFAULT_PRESSES_PER_PAGE;
    }

    public void setPressesPerPage(int presses) {
        this.sharedPreferences.edit().putInt(KEY_PRESSES_PER_PAGE, clampPresses(presses)).apply();
    }

    public boolean isShortcutToggleEnabled() {
        return this.sharedPreferences.getBoolean(KEY_SHORTCUT_TOGGLE_ENABLED, true);
    }

    public void setShortcutToggleEnabled(boolean enabled) {
        this.sharedPreferences.edit().putBoolean(KEY_SHORTCUT_TOGGLE_ENABLED, enabled).apply();
    }

    /**
     * When true, volume keys scroll only inside the apps selected via
     * "Select apps". Default ON since 1.7.0: out of the box the app scrolls
     * only in Firefox and Loki.
     */
    public boolean isAppFilterEnabled() {
        return this.sharedPreferences.getBoolean(KEY_APP_FILTER_ENABLED, true);
    }

    public void setAppFilterEnabled(boolean enabled) {
        this.sharedPreferences.edit().putBoolean(KEY_APP_FILTER_ENABLED, enabled).apply();
    }

    /**
     * Apps volume scrolling works in while the filter is enabled. Default:
     * Firefox and Loki (this app) — applied both for fresh installs and, once,
     * for existing installs (see {@link #applyScrollAppDefaultsIfNeeded()}).
     */
    public Set<String> getAllowedAppPackages() {
        Set<String> saved = this.sharedPreferences.getStringSet(KEY_ALLOWED_APP_PACKAGES, null);
        if (saved == null) {
            return defaultScrollPackages();
        }
        return new HashSet<String>(saved);
    }

    private Set<String> defaultScrollPackages() {
        Set<String> defaults = new HashSet<String>();
        defaults.add(DEFAULT_SCROLL_PACKAGE_FIREFOX);
        defaults.add(this.appContext.getPackageName());
        return defaults;
    }

    /**
     * One-time migration to the 1.7.0 defaults: scrolling restricted to
     * Firefox and Loki. Runs once per install (marker preference), then all
     * values stay fully user-controlled.
     */
    public void applyScrollAppDefaultsIfNeeded() {
        if (this.sharedPreferences.getBoolean(KEY_SCROLL_DEFAULTS_V2, false)) {
            return;
        }
        this.sharedPreferences.edit()
                .putBoolean(KEY_SCROLL_DEFAULTS_V2, true)
                .putBoolean(KEY_APP_FILTER_ENABLED, true)
                .putStringSet(KEY_ALLOWED_APP_PACKAGES, defaultScrollPackages())
                .apply();
    }

    public void setAllowedAppPackages(Set<String> packages) {
        this.sharedPreferences.edit().putStringSet(KEY_ALLOWED_APP_PACKAGES, new HashSet<String>(packages)).apply();
    }

    public boolean isStepStatsCalendarMode() {
        return this.sharedPreferences.getBoolean(KEY_STEP_STATS_CALENDAR_MODE, true);
    }

    public void setStepStatsCalendarMode(boolean calendarMode) {
        this.sharedPreferences.edit().putBoolean(KEY_STEP_STATS_CALENDAR_MODE, calendarMode).apply();
    }

    // ------------------------------------------------------------------
    // Step stats bottom tab: Calendar / Rolling / Graph / Speed
    // ------------------------------------------------------------------

    public static final int TAB_CALENDAR = 0;
    public static final int TAB_ROLLING = 1;
    public static final int TAB_GRAPH = 2;
    public static final int TAB_SPEED = 3;

    /**
     * Selected bottom tab. Migrated from the old calendar/rolling boolean on
     * first read (calendar mode = Calendar tab, rolling = Rolling tab).
     */
    public int getStepStatsTab() {
        if (this.sharedPreferences.contains(KEY_STEP_STATS_TAB)) {
            int tab = this.sharedPreferences.getInt(KEY_STEP_STATS_TAB, TAB_CALENDAR);
            if (tab < TAB_CALENDAR || tab > TAB_SPEED) {
                return TAB_CALENDAR;
            }
            return tab;
        }
        return isStepStatsCalendarMode() ? TAB_CALENDAR : TAB_ROLLING;
    }

    public void setStepStatsTab(int tab) {
        int safe = Math.max(TAB_CALENDAR, Math.min(tab, TAB_SPEED));
        this.sharedPreferences.edit()
                .putInt(KEY_STEP_STATS_TAB, safe)
                .putBoolean(KEY_STEP_STATS_CALENDAR_MODE, safe == TAB_CALENDAR)
                .apply();
    }

    // ------------------------------------------------------------------
    // Folder player state (remembers the last track across restarts)
    // ------------------------------------------------------------------

    /**
     * Local directory containing the music that was last picked through the
     * file selector (the folder of the picked file). Persistent: the folder
     * is rescanned directly on the filesystem, no SAF grant needed.
     * Takes priority over picked files / folder tree / default scan.
     */
    public String getPlayerFolderDir() {
        return this.sharedPreferences.getString(KEY_PLAYER_FOLDER_DIR, null);
    }

    public void setPlayerFolderDir(String dirPath) {
        this.sharedPreferences.edit().putString(KEY_PLAYER_FOLDER_DIR, dirPath).apply();
    }

    /**
     * One-shot marker: the file the user picked, to start playing from it.
     * Consumed (cleared) by the player after locating it in the library.
     */
    public String getPlayerStartFile() {
        return this.sharedPreferences.getString(KEY_PLAYER_START_FILE, null);
    }

    public void setPlayerStartFile(String filePath) {
        this.sharedPreferences.edit().putString(KEY_PLAYER_START_FILE, filePath).apply();
    }

    /**
     * Individual files picked with the system file selector (audio or video;
     * video is played audio-only), one URI per line. Fallback for documents
     * that do not live on local storage (e.g. cloud files).
     */
    public List<String> getPlayerFileUris() {
        String raw = this.sharedPreferences.getString(KEY_PLAYER_FILE_URIS, "");
        List<String> uris = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split("\\n")) {
                if (!part.trim().isEmpty()) {
                    uris.add(part.trim());
                }
            }
        }
        return uris;
    }

    public void setPlayerFileUris(List<String> uris) {
        String raw = (uris == null || uris.isEmpty())
                ? "" : android.text.TextUtils.join("\n", uris);
        this.sharedPreferences.edit().putString(KEY_PLAYER_FILE_URIS, raw).apply();
    }

    public String getPlayerTreeUri() {
        return this.sharedPreferences.getString(KEY_PLAYER_TREE_URI, null);
    }

    public void setPlayerTreeUri(String treeUri) {
        this.sharedPreferences.edit().putString(KEY_PLAYER_TREE_URI, treeUri).apply();
    }

    public String getPlayerLastFolder() {
        return this.sharedPreferences.getString(KEY_PLAYER_LAST_FOLDER, null);
    }

    public String getPlayerLastTrack() {
        return this.sharedPreferences.getString(KEY_PLAYER_LAST_TRACK, null);
    }

    public void setPlayerLastSelection(String folderName, String trackName) {
        this.sharedPreferences.edit()
                .putString(KEY_PLAYER_LAST_FOLDER, folderName)
                .putString(KEY_PLAYER_LAST_TRACK, trackName)
                .apply();
    }

    public int getPlayerLastPositionMs() {
        return this.sharedPreferences.getInt(KEY_PLAYER_LAST_POSITION_MS, 0);
    }

    public void setPlayerLastPositionMs(int positionMs) {
        this.sharedPreferences.edit().putInt(KEY_PLAYER_LAST_POSITION_MS, Math.max(0, positionMs)).apply();
    }

    public boolean getPlayerRandom() {
        return this.sharedPreferences.getBoolean(KEY_PLAYER_RANDOM, false);
    }

    public void setPlayerRandom(boolean random) {
        this.sharedPreferences.edit().putBoolean(KEY_PLAYER_RANDOM, random).apply();
    }

    /** Seconds the \u2212N s button loops / rewinds (1..30, default 5). */
    public int getPlayerLoopSeconds() {
        return clampLoopSeconds(this.sharedPreferences.getInt(KEY_PLAYER_LOOP_SECONDS, DEFAULT_LOOP_SECONDS));
    }

    public void setPlayerLoopSeconds(int seconds) {
        this.sharedPreferences.edit().putInt(KEY_PLAYER_LOOP_SECONDS, clampLoopSeconds(seconds)).apply();
    }

    /** Clamps the loop window into [MIN_LOOP_SECONDS, MAX_LOOP_SECONDS]. */
    public static int clampLoopSeconds(int seconds) {
        return Math.max(MIN_LOOP_SECONDS, Math.min(seconds, MAX_LOOP_SECONDS));
    }

    // ------------------------------------------------------------------
    // Speed warnings (steps per minute; 0 = off)
    // ------------------------------------------------------------------

    /** Warn when the live speed stays above this value for 5 minutes. */
    public int getSpeedWarnHigh() {
        return clampSpeedWarn(this.sharedPreferences.getInt(
                KEY_SPEED_WARN_HIGH, DEFAULT_SPEED_WARN_HIGH));
    }

    public void setSpeedWarnHigh(int stepsPerMinute) {
        this.sharedPreferences.edit().putInt(KEY_SPEED_WARN_HIGH, clampSpeedWarn(stepsPerMinute)).apply();
    }

    /** Warn when the live speed stays below this value for 5 minutes. */
    public int getSpeedWarnLow() {
        return clampSpeedWarn(this.sharedPreferences.getInt(
                KEY_SPEED_WARN_LOW, DEFAULT_SPEED_WARN_LOW));
    }

    public void setSpeedWarnLow(int stepsPerMinute) {
        this.sharedPreferences.edit().putInt(KEY_SPEED_WARN_LOW, clampSpeedWarn(stepsPerMinute)).apply();
    }

    private static int clampSpeedWarn(int value) {
        return Math.max(MIN_SPEED_WARN, Math.min(value, MAX_SPEED_WARN));
    }

    public void registerChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        this.sharedPreferences.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        this.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private static int clampPresses(int presses) {
        return Math.max(MIN_PRESSES_PER_PAGE, Math.min(presses, MAX_PRESSES_PER_PAGE));
    }
}
