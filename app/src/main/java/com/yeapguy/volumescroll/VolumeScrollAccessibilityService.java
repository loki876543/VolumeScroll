package com.yeapguy.volumescroll;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Receives every key event while the service is enabled.
 *
 * Screen on: volume keys scroll the foreground app. Each press fires a fast
 * center swipe (1/N of the screen, N = presses per page, default 4) that
 * releases with velocity, so the content glides on instantly — the v1.1.0
 * "fast scrolling" feel.
 *
 * Player running: double volume press switches tracks, triple press switches
 * folders. Works with the screen off; with the screen on it takes over only
 * when volume scrolling is disabled (otherwise the keys keep scrolling).
 */
public class VolumeScrollAccessibilityService extends AccessibilityService {

    private static final long CHORD_WINDOW_MS = 50L;
    /** How close the two volume keys must be pressed to count as a chord (player mode). */
    private static final long PLAYER_CHORD_WINDOW_MS = 350L;
    private static final long PLAYER_PRESS_WINDOW_MS = 750L;
    /** Duration of the fast scroll swipe (v1.1.0 speed). */
    private static final long FAST_SCROLL_DURATION_MS = 90L;
    private static final int SEARCH_MAX_CHILDREN = 150;
    private static final int SEARCH_MAX_DEPTH = 6;

    private SettingsRepository settingsRepository;
    private PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean volumeUpPressed = false;
    private boolean volumeDownPressed = false;
    private boolean volumeUpPassedToSystem = false;
    private boolean volumeDownPassedToSystem = false;
    private boolean chordConsumed = false;
    private String latestForegroundPackage;
    private int pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private int pendingToken = 0;
    private Runnable pendingScrollRunnable;

    private int playerPressCount = 0;
    private int playerPressKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private int playerPressToken = 0;
    private Runnable playerPressRunnable;
    private long lastPlayerDownAt = 0L;
    private int lastPlayerDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    private View feedbackOverlayView;
    private WindowManager windowManager;
    private final Runnable removeFeedbackOverlayRunnable = this::removeFeedbackOverlay;

    @Override
    public void onCreate() {
        super.onCreate();
        settingsRepository = new SettingsRepository(this);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        windowManager = getSystemService(WindowManager.class);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.flags = info.flags
                | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        latestForegroundPackage = event.getPackageName().toString();
    }

    @Override
    public void onInterrupt() {
        cancelPendingScroll();
        removeFeedbackOverlay();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFeedbackOverlay();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event);
        }

        boolean featureEnabled = settingsRepository.isFeatureEnabled();
        boolean shortcutToggleEnabled = settingsRepository.isShortcutToggleEnabled();

        boolean playerGestureMode = isPlayerGestureMode();
        boolean screenOnPlayerMode = isScreenOnPlayerMode();

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return playerGestureMode || screenOnPlayerMode || featureEnabled;
            }

            if (playerGestureMode || screenOnPlayerMode) {
                // Music player active: double/triple presses drive the player.
                // Screen off: always. Screen on: only when volume scrolling is
                // disabled, so the keys are free for the player.
                handlePlayerGesturePress(keyCode);
                return true;
            }

            if (powerManager != null && !powerManager.isInteractive()) {
                // Screen is off and the player is not active: nothing to scroll,
                // let the system handle the volume keys normally.
                markKeyPassedToSystem(keyCode, true);
                return false;
            }

            setPressedState(keyCode, true);

            if (shortcutToggleEnabled && volumeUpPressed && volumeDownPressed) {
                cancelPendingScroll();
                if (!chordConsumed) {
                    handleVolumeChord();
                    chordConsumed = true;
                }
                return true;
            }

            if (!featureEnabled) {
                // Feature is off: do not consume single volume keys so Android can adjust volume.
                markKeyPassedToSystem(keyCode, true);
                return false;
            }

            if (!isScrollAllowedForForegroundApp()) {
                // App filter is enabled and current app is not allowed: pass volume keys through.
                markKeyPassedToSystem(keyCode, true);
                return false;
            }

            if (shortcutToggleEnabled) {
                schedulePendingScroll(keyCode);
            } else {
                cancelPendingScroll();
                executeScrollForKeyCode(keyCode);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (playerGestureMode || screenOnPlayerMode) {
                return true; // keep the release consumed in player gesture mode
            }
            setPressedState(keyCode, false);
            if (wasKeyPassedToSystem(keyCode)) {
                markKeyPassedToSystem(keyCode, false);
                return false;
            }
            if (!volumeUpPressed && !volumeDownPressed) {
                chordConsumed = false;
            }
            return featureEnabled;
        }

        return super.onKeyEvent(event);
    }

    // ---------------------------------------------------------------------
    // Fast scrolling (v1.1.0 style)
    // ---------------------------------------------------------------------

    private void executeScrollForKeyCode(int keyCode) {
        if (!settingsRepository.isFeatureEnabled()) {
            return;
        }
        if (!isScrollAllowedForForegroundApp()) {
            return;
        }

        boolean shouldScrollForward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (settingsRepository.isInvertDirectionEnabled()) {
            shouldScrollForward = !shouldScrollForward;
        }

        performFastScroll(shouldScrollForward);
    }

    /**
     * Fires a fast center swipe over 1/N of the screen height (N = presses
     * per page) and releases it with velocity: the content starts moving
     * immediately and glides on for a moment — the v1.1.0 fast feel. The
     * release velocity intentionally causes a short fling, so the actual
     * distance is "press distance plus glide" rather than pixel-exact.
     */
    private void performFastScroll(boolean forward) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        if (screenWidth <= 0 || screenHeight <= 0) {
            performFallbackScroll(forward);
            return;
        }

        int presses = settingsRepository.getPressesPerPage();
        int edgeMargin = Math.max(8, Math.round(6 * metrics.density));
        int usable = screenHeight - 2 * edgeMargin;
        if (usable <= 40) {
            performFallbackScroll(forward);
            return;
        }
        int distance = Math.max(1, usable / presses);

        float x = screenWidth * 0.5f;
        float centerY = screenHeight * 0.5f;
        float halfDistance = distance * 0.5f;
        float startY = forward ? centerY + halfDistance : centerY - halfDistance;
        float endY = forward ? centerY - halfDistance : centerY + halfDistance;

        Path swipePath = new Path();
        swipePath.moveTo(x, startY);
        swipePath.lineTo(x, endY);
        GestureDescription.StrokeDescription swipeStroke =
                new GestureDescription.StrokeDescription(swipePath, 0, FAST_SCROLL_DURATION_MS, false);
        GestureDescription swipeGesture =
                new GestureDescription.Builder().addStroke(swipeStroke).build();

        boolean dispatched = dispatchGesture(swipeGesture, null, null);
        if (!dispatched) {
            performFallbackScroll(forward);
        }
    }

    /** Fallback for devices that refuse synthesized gestures: one page-style node action. */
    private void performFallbackScroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo target = findBestScrollableNode(root, forward);
        if (target == null) {
            return;
        }
        int action = forward
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();
        target.performAction(action);
    }

    // ---------------------------------------------------------------------
    // Player gestures while the screen is off
    // ---------------------------------------------------------------------

    private boolean isPlayerGestureMode() {
        return powerManager != null
                && !powerManager.isInteractive()
                && FolderPlayerService.isRunning();
    }

    /**
     * Player gestures while the screen is ON: only when volume scrolling is
     * disabled, so the keys are not needed for scrolling.
     */
    private boolean isScreenOnPlayerMode() {
        return powerManager != null
                && powerManager.isInteractive()
                && FolderPlayerService.isRunning()
                && !settingsRepository.isFeatureEnabled();
    }

    private void handlePlayerGesturePress(int keyCode) {
        long now = SystemClock.elapsedRealtime();
        if (playerPressCount > 0 && keyCode != playerPressKeyCode) {
            if (now - lastPlayerDownAt <= PLAYER_CHORD_WINDOW_MS
                    && lastPlayerDownKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                // Both volume keys pressed together: switch random.
                cancelPlayerPresses();
                handleVolumeChord();
                lastPlayerDownAt = now;
                lastPlayerDownKeyCode = keyCode;
                return;
            }
            // Mixed keys with a large gap: drop the partial sequence.
            cancelPlayerPresses();
        }
        lastPlayerDownAt = now;
        lastPlayerDownKeyCode = keyCode;
        playerPressKeyCode = keyCode;
        playerPressCount++;
        final int token = ++playerPressToken;
        if (playerPressRunnable != null) {
            handler.removeCallbacks(playerPressRunnable);
        }
        playerPressRunnable = () -> executePlayerPresses(token);
        handler.postDelayed(playerPressRunnable, PLAYER_PRESS_WINDOW_MS);
    }

    private void executePlayerPresses(int token) {
        if (token != playerPressToken) {
            return;
        }
        playerPressRunnable = null;
        int count = playerPressCount;
        int keyCode = playerPressKeyCode;
        playerPressCount = 0;
        playerPressKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        if (count < 2) {
            return; // single press: intentionally unused, volume stays untouched
        }

        boolean isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        if (count >= 3) {
            FolderPlayerService.startCommand(this,
                    isUp ? FolderPlayerService.ACTION_NEXT_FOLDER : FolderPlayerService.ACTION_PREV_FOLDER);
        } else {
            FolderPlayerService.startCommand(this,
                    isUp ? FolderPlayerService.ACTION_NEXT : FolderPlayerService.ACTION_PREV);
        }
        vibratePlayerFeedback();
    }

    private void cancelPlayerPresses() {
        playerPressToken++;
        playerPressCount = 0;
        playerPressKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        if (playerPressRunnable != null) {
            handler.removeCallbacks(playerPressRunnable);
            playerPressRunnable = null;
        }
    }

    private void vibratePlayerFeedback() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = getSystemService(VibratorManager.class);
            vibrator = vibratorManager == null ? null : vibratorManager.getDefaultVibrator();
        } else {
            vibrator = getSystemService(Vibrator.class);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ---------------------------------------------------------------------
    // Chord / pending scroll helpers (unchanged behaviour)
    // ---------------------------------------------------------------------

    private void schedulePendingScroll(int keyCode) {
        cancelPendingScroll();
        pendingKeyCode = keyCode;
        int token = ++pendingToken;
        pendingScrollRunnable = () -> executePendingScroll(token);
        handler.postDelayed(pendingScrollRunnable, CHORD_WINDOW_MS);
    }

    private void executePendingScroll(int token) {
        pendingScrollRunnable = null;
        if (token != pendingToken) {
            return;
        }
        if (volumeUpPressed && volumeDownPressed) {
            return;
        }
        int keyCode = pendingKeyCode;
        pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        executeScrollForKeyCode(keyCode);
    }

    private void cancelPendingScroll() {
        pendingToken++;
        pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        if (pendingScrollRunnable != null) {
            handler.removeCallbacks(pendingScrollRunnable);
            pendingScrollRunnable = null;
        }
    }

    private boolean isScrollAllowedForForegroundApp() {
        if (!settingsRepository.isAppFilterEnabled()) {
            return true;
        }

        Set<String> allowedPackages = settingsRepository.getAllowedAppPackages();
        if (allowedPackages.isEmpty()) {
            return false;
        }

        String foregroundPackage = getForegroundPackageName();
        if (foregroundPackage == null || foregroundPackage.isEmpty()) {
            return false;
        }
        return allowedPackages.contains(foregroundPackage);
    }

    private String getForegroundPackageName() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            return root.getPackageName().toString();
        }
        return latestForegroundPackage;
    }

    private void setPressedState(int keyCode, boolean pressed) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPressed = pressed;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPressed = pressed;
        }
    }

    private void markKeyPassedToSystem(int keyCode, boolean passedToSystem) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpPassedToSystem = passedToSystem;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPassedToSystem = passedToSystem;
        }
    }

    private boolean wasKeyPassedToSystem(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return volumeUpPassedToSystem;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return volumeDownPassedToSystem;
        }
        return false;
    }

    /**
     * Both volume keys pressed together. With the music player running this
     * switches random playback on/off; without the player it toggles volume
     * scrolling on/off as before.
     */
    private void handleVolumeChord() {
        if (FolderPlayerService.isRunning()) {
            boolean wasRandom = settingsRepository.getPlayerRandom();
            FolderPlayerService.startCommand(this, FolderPlayerService.ACTION_TOGGLE_RANDOM);
            handler.post(() -> {
                showFeedbackOverlay(getString(wasRandom
                        ? R.string.overlay_random_off : R.string.overlay_random_on));
                vibratePlayerFeedback();
            });
        } else {
            toggleFeatureEnabled();
        }
    }

    private void toggleFeatureEnabled() {
        boolean newEnabled = !settingsRepository.isFeatureEnabled();
        settingsRepository.setFeatureEnabled(newEnabled);
        handler.post(() -> {
            int feedbackRes = newEnabled
                    ? R.string.overlay_shortcut_enabled
                    : R.string.overlay_shortcut_disabled;
            showFeedbackOverlay(getString(feedbackRes));
            vibratePlayerFeedback();
        });
    }

    // ---------------------------------------------------------------------
    // Node search (fallback scrolling)
    // ---------------------------------------------------------------------

    private AccessibilityNodeInfo findBestScrollableNode(AccessibilityNodeInfo root, boolean forward) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        AccessibilityNodeInfo fromFocusedTree = findScrollableInAncestors(focused, forward);
        if (fromFocusedTree != null) {
            return fromFocusedTree;
        }

        AccessibilityNodeInfo accessibilityFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        AccessibilityNodeInfo fromA11yFocusedTree = findScrollableInAncestors(accessibilityFocused, forward);
        if (fromA11yFocusedTree != null) {
            return fromA11yFocusedTree;
        }

        return breadthFirstScrollableSearch(root, forward);
    }

    private AccessibilityNodeInfo findScrollableInAncestors(AccessibilityNodeInfo node, boolean forward) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (canScroll(current, forward)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo breadthFirstScrollableSearch(AccessibilityNodeInfo root, boolean forward) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        int childrenFetched = 0;

        while (!queue.isEmpty() && childrenFetched < SEARCH_MAX_CHILDREN) {
            AccessibilityNodeInfo node = queue.removeFirst();
            int depth = depths.removeFirst();
            if (node == null) {
                continue;
            }
            if (canScroll(node, forward)) {
                return node;
            }
            if (depth < SEARCH_MAX_DEPTH) {
                int childCount = node.getChildCount();
                childrenFetched += childCount;
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                        depths.addLast(depth + 1);
                    }
                }
            }
        }
        return null;
    }

    private boolean canScroll(AccessibilityNodeInfo node, boolean forward) {
        if (!node.isScrollable()) {
            return false;
        }
        int requiredActionId = forward
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();

        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action != null && action.getId() == requiredActionId) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Feedback overlay
    // ---------------------------------------------------------------------

    private void showFeedbackOverlay(String message) {
        if (windowManager == null) {
            return;
        }

        removeFeedbackOverlay();

        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(15f);
        textView.setPadding(34, 22, 34, 22);
        textView.setBackgroundColor(0xD9202020);

        FrameLayout container = new FrameLayout(this);
        container.setClickable(false);
        container.setFocusable(false);
        container.addView(textView);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 110;

        try {
            windowManager.addView(container, params);
            feedbackOverlayView = container;
            handler.postDelayed(removeFeedbackOverlayRunnable, 1200L);
        } catch (RuntimeException ignored) {
            feedbackOverlayView = null;
        }
    }

    private void removeFeedbackOverlay() {
        handler.removeCallbacks(removeFeedbackOverlayRunnable);
        if (feedbackOverlayView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(feedbackOverlayView);
        } catch (RuntimeException ignored) {
            // View can already be detached on rapid toggle spam.
        }
        feedbackOverlayView = null;
    }
}
