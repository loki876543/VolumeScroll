package com.yeapguy.volumescroll;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.net.Uri;
import android.widget.RemoteViews;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Folder-based music player.
 *
 * Plays audio files from a folder tree. By default the Music folders of
 * internal storage and SD cards are scanned; the user can also pick any file
 * with the system file selector — its folder becomes the playlist and
 * playback starts at the picked file — or pick a whole folder with the
 * system folder selector (SAF tree URI, persisted).
 * Shows plain folder/file names (no ID3 tags).
 *
 * The library is NOT scanned on start: the player starts instantly and only
 * scans when playback is first requested, then resumes the last folder,
 * track and position (persisted in SharedPreferences).
 *
 * Provides a media notification with rewind/prev/play/next/folder buttons,
 * pauses when headphones are unplugged and resumes when they come back, and
 * ducks on transient audio-focus loss. The service keeps running while
 * paused — the notification is always ongoing and the service is sticky, so
 * even if the system kills the process after a long pause it comes back
 * (paused, never auto-playing) and screen-off volume gestures stay available.
 *
 * Random mode plays random files of the current folder. Switching folders
 * (Folder+/\u2212, double/triple volume presses) always walks the folders in
 * order — folders are never shuffled — but starts at a random file of the
 * target folder when random is on.
 */
public class FolderPlayerService extends Service {

    public static final String ACTION_START = "com.yeapguy.volumescroll.player.START";
    public static final String ACTION_TOGGLE = "com.yeapguy.volumescroll.player.TOGGLE_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.yeapguy.volumescroll.player.NEXT_TRACK";
    public static final String ACTION_PREV = "com.yeapguy.volumescroll.player.PREV_TRACK";
    public static final String ACTION_NEXT_FOLDER = "com.yeapguy.volumescroll.player.NEXT_FOLDER";
    public static final String ACTION_PREV_FOLDER = "com.yeapguy.volumescroll.player.PREV_FOLDER";
    public static final String ACTION_REWIND_5S = "com.yeapguy.volumescroll.player.REWIND_5S";
    public static final String ACTION_TOGGLE_LOOP_5S = "com.yeapguy.volumescroll.player.TOGGLE_LOOP_5S";
    public static final String ACTION_SEEK = "com.yeapguy.volumescroll.player.SEEK";
    public static final String ACTION_SEEK_TO = "com.yeapguy.volumescroll.player.SEEK_TO";
    public static final String EXTRA_SEEK_MS = "seek_ms";
    public static final String ACTION_SET_LOOP_SECONDS = "com.yeapguy.volumescroll.player.SET_LOOP_SECONDS";
    public static final String EXTRA_LOOP_SECONDS = "loop_seconds";
    public static final String ACTION_TOGGLE_RANDOM = "com.yeapguy.volumescroll.player.TOGGLE_RANDOM";
    public static final String ACTION_RELOAD = "com.yeapguy.volumescroll.player.RELOAD_LIBRARY";
    public static final String ACTION_STOP = "com.yeapguy.volumescroll.player.STOP";
    /** Content-intent extra: the notification was tapped — open the player UI. */
    public static final String EXTRA_OPEN_PLAYER = "open_player";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "music_player";

    /** Loop seconds reported before the service has loaded its settings. */
    private static final int DEFAULT_LOOP_SECONDS_FALLBACK =
            SettingsRepository.DEFAULT_LOOP_SECONDS;

    private static volatile FolderPlayerService sInstance;

    public static boolean isRunning() {
        return sInstance != null;
    }

    /** Sends a command to the player; only meaningful while isRunning() is true. */
    public static void startCommand(Context context, String action) {
        try {
            context.startService(new Intent(context, FolderPlayerService.class).setAction(action));
        } catch (RuntimeException ignored) {
            // Service may be shutting down.
        }
    }

    /** Folder names of the current library, for the in-app folder picker. */
    public static List<String> getFolderNames() {
        FolderPlayerService service = sInstance;
        if (service == null || service.folders == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (MusicLibrary.Folder folder : service.folders) {
            names.add(folder.name);
        }
        return names;
    }

    /** Immutable snapshot of the playback state, polled by the UI once per second. */
    public static final class Snapshot {
        public static final Snapshot IDLE = new Snapshot(false, false, false, false, false, false, null,
                null, null, 0, 0, 0, 0, 0, 0, DEFAULT_LOOP_SECONDS_FALLBACK);

        public final boolean ready;       // library scanned and at least one track found
        public final boolean playing;
        public final boolean random;
        public final boolean scanning;
        public final boolean loopActive;  // \u22125 s loop is engaged
        public final boolean customFolder; // library comes from picked files or folder tree
        public final String error;        // non-null when playback is impossible
        public final String fileName;     // e.g. "track 07.mp3"
        public final String folderName;   // e.g. "Music/2023"
        public final int folderIndex;
        public final int folderCount;
        public final int trackIndex;
        public final int trackCount;
        public final int positionMs;
        public final int durationMs;
        public final int loopSeconds;     // current loop window of the \u2212N s button

        Snapshot(boolean ready, boolean playing, boolean random, boolean scanning,
                 boolean loopActive, boolean customFolder, String error,
                 String fileName, String folderName,
                 int folderIndex, int folderCount, int trackIndex, int trackCount,
                 int positionMs, int durationMs, int loopSeconds) {
            this.ready = ready;
            this.playing = playing;
            this.random = random;
            this.scanning = scanning;
            this.loopActive = loopActive;
            this.customFolder = customFolder;
            this.error = error;
            this.fileName = fileName;
            this.folderName = folderName;
            this.folderIndex = folderIndex;
            this.folderCount = folderCount;
            this.trackIndex = trackIndex;
            this.trackCount = trackCount;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.loopSeconds = loopSeconds;
        }
    }

    public static volatile Snapshot CURRENT = Snapshot.IDLE;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private SettingsRepository settingsRepository;

    private volatile List<MusicLibrary.Folder> folders = Collections.emptyList();
    private int folderIdx = 0;
    private int trackIdx = 0;
    private boolean playing = false;
    private boolean random = false;
    private boolean scanning = false;
    private boolean scanned = false;
    private boolean dataSourceSet = false;
    private boolean focusHeld = false;
    private boolean resumeOnFocusGain = false;
    private boolean ducked = false;
    private boolean pausedByHeadset = false;
    private boolean customFolder = false;
    private boolean loopActive = false;
    private int loopStartMs = 0;
    /** Loop window of the \u2212N s button (seconds), swipe on the button changes it. */
    private int loopSeconds = SettingsRepository.DEFAULT_LOOP_SECONDS;
    private String error = null;
    private final Random rng = new Random();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    /** While the loop is engaged, keeps playback inside [loopStartMs, loopStartMs+N seconds). */
    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!loopActive) {
                return;
            }
            executor.execute(() -> {
                if (loopActive && playing && dataSourceSet && player != null) {
                    try {
                        int pos = player.getCurrentPosition();
                        int windowMs = loopSeconds * 1000;
                        if (pos >= loopStartMs + windowMs || pos < loopStartMs - 500) {
                            player.seekTo(loopStartMs);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            });
            mainHandler.postDelayed(this, 250L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        settingsRepository = new SettingsRepository(this);
        random = settingsRepository.getPlayerRandom();
        loopSeconds = settingsRepository.getPlayerLoopSeconds();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        player = new MediaPlayer();
        player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnCompletionListener(mp ->
                executor.execute(() -> {
                    if (playing) {
                        // The track finished on its own: forget its position so
                        // replaying it later starts from the beginning.
                        settingsRepository.setPlayerLastPositionMs(0);
                        advanceTrack(1, 0);
                    }
                }));

        session = new MediaSession(this, "VolumeScrollFolderPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlay() {
                executor.execute(() -> playOrResume());
            }

            @Override
            public void onPause() {
                executor.execute(() -> pause(false));
            }

            @Override
            public void onSkipToNext() {
                executor.execute(() -> advanceTrack(1, 0));
            }

            @Override
            public void onSkipToPrevious() {
                executor.execute(() -> advanceTrack(-1, 0));
            }

            @Override
            public void onRewind() {
                executor.execute(() -> rewind5s());
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);

        AudioFocusRequest.Builder focusBuilder = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChanged);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            focusBuilder.setWillPauseWhenDucked(true);
        }
        focusRequest = focusBuilder.build();

        // Headphones plugged in / out. The callback fires for wired headsets,
        // USB and Bluetooth; the BECOMING_NOISY broadcast is the reliable
        // signal that the output switched back to the speaker (unplug).
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        try {
            registerReceiver(becomingNoisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (RuntimeException ignored) {
        }

        // Saves the position every second while playing so the last track
        // (and where it was left off) survives process death.
        ticker = new Runnable() {
            @Override
            public void run() {
                if (playing && dataSourceSet) {
                    savePosition();
                    // Update every second so the notification progress bar
                    // (scrollbar) tracks playback.
                    syncState(true);
                }
                mainHandler.postDelayed(this, 1000L);
            }
        };
        mainHandler.postDelayed(ticker, 1000L);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent == null) {
            // Sticky restart after the system killed the process (e.g. during
            // a long pause): come back as a paused/idle player with its
            // notification. Never auto-play; the next Play resumes from the
            // saved position.
            executor.execute(() -> syncState(true));
            return START_STICKY;
        }
        final String action = intent.getAction() == null
                ? ACTION_START : intent.getAction();
        executor.execute(() -> handleAction(action, intent));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // Persist the current selection one last time (best effort).
        try {
            if (dataSourceSet && player != null) {
                settingsRepository.setPlayerLastPositionMs(Math.max(0, player.getCurrentPosition()));
            }
        } catch (RuntimeException ignored) {
        }
        mainHandler.removeCallbacks(ticker);
        mainHandler.removeCallbacks(loopRunnable);
        loopActive = false;
        executor.shutdownNow();
        try {
            unregisterReceiver(becomingNoisyReceiver);
        } catch (RuntimeException ignored) {
        }
        if (audioManager != null) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
            audioManager.unregisterAudioDeviceCallback(deviceCallback);
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        sInstance = null;
        CURRENT = Snapshot.IDLE;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Command handling (executed on the single worker thread)
    // ------------------------------------------------------------------

    private void handleAction(String action, Intent intent) {
        switch (action) {
            case ACTION_TOGGLE:
                if (playing) {
                    pause(false);
                } else {
                    playOrResume();
                }
                break;
            case ACTION_NEXT:
                advanceTrack(1, 0);
                break;
            case ACTION_PREV:
                advanceTrack(-1, 0);
                break;
            case ACTION_NEXT_FOLDER:
                switchFolder(1);
                break;
            case ACTION_PREV_FOLDER:
                switchFolder(-1);
                break;
            case ACTION_REWIND_5S:
                // The -5s button: first press starts looping the previous 5
                // seconds, second press stops the loop and plays on.
            case ACTION_TOGGLE_LOOP_5S:
                toggleLoop5s();
                break;
            case ACTION_SEEK:
                int ms = intent != null ? intent.getIntExtra(EXTRA_SEEK_MS, 0) : 0;
                seekRelative(ms);
                break;
            case ACTION_SEEK_TO:
                int target = intent != null ? intent.getIntExtra(EXTRA_SEEK_MS, 0) : 0;
                seekToAbsolute(target);
                break;
            case ACTION_TOGGLE_RANDOM:
                random = !random;
                settingsRepository.setPlayerRandom(random);
                syncState(true);
                break;
            case ACTION_SET_LOOP_SECONDS:
                int requested = intent != null ? intent.getIntExtra(EXTRA_LOOP_SECONDS, 0) : 0;
                if (requested > 0) {
                    loopSeconds = SettingsRepository.clampLoopSeconds(requested);
                    settingsRepository.setPlayerLoopSeconds(loopSeconds);
                }
                syncState(true);
                break;
            case ACTION_STOP:
                savePosition();
                mainHandler.post(this::stopSelf);
                break;
            case ACTION_RELOAD:
                // The music source changed (system folder picker): drop the
                // cached library and playback state, then start again.
                scanned = false;
                error = null;
                if (dataSourceSet && player != null) {
                    player.reset();
                    dataSourceSet = false;
                    playing = false;
                }
                playOrResume();
                break;
            case ACTION_START:
            default:
                playOrResume();
                break;
        }
    }

    /**
     * Scans the library — but only when playback is actually requested, never
     * just because the service started. The source is, in priority order:
     * the local folder of the file picked with the system file selector (the
     * picked file itself starts playback), the individually picked files
     * (fallback for non-local documents), the folder tree picked with the
     * system folder selector (persisted), or the default Music folders when
     * nothing was picked. After the scan the remembered folder/track is
     * restored — unless the picked file marker overrides it.
     */
    private void ensureLibrary() {
        if (scanned) {
            return;
        }
        scanning = true;
        syncState(true);
        List<Uri> pickedFiles = new ArrayList<>();
        for (String s : settingsRepository.getPlayerFileUris()) {
            try {
                pickedFiles.add(Uri.parse(s));
            } catch (RuntimeException ignored) {
            }
        }
        Uri treeUri = parseTreeUri(settingsRepository.getPlayerTreeUri());
        String folderDir = settingsRepository.getPlayerFolderDir();
        File pickedDir = folderDir == null || folderDir.isEmpty()
                ? null : new File(folderDir);
        boolean custom;
        if (pickedDir != null && pickedDir.isDirectory()) {
            try {
                folders = MusicLibrary.scanDirectory(pickedDir);
            } catch (RuntimeException e) {
                folders = Collections.emptyList();
            }
            custom = true;
            if (folders.isEmpty()) {
                error = getString(R.string.player_status_no_music_in_folder);
                scanning = false;
                scanned = true;
                customFolder = custom;
                return;
            }
            if (!applyStartFile()) {
                restoreSavedSelection();
            }
        } else if (!pickedFiles.isEmpty()) {
            try {
                folders = MusicLibrary.buildFromFiles(this, pickedFiles);
            } catch (RuntimeException e) {
                folders = Collections.emptyList();
            }
            custom = true;
            restoreSavedSelection();
        } else if (treeUri != null) {
            try {
                folders = MusicLibrary.scanTree(this, treeUri);
            } catch (RuntimeException e) {
                folders = Collections.emptyList();
            }
            custom = true;
            restoreSavedSelection();
        } else {
            folders = MusicLibrary.scan();
            custom = false;
            restoreSavedSelection();
        }
        scanning = false;
        scanned = true;
        customFolder = custom;
        if (folders.isEmpty()) {
            error = getString(custom
                    ? R.string.player_status_no_music_in_folder
                    : R.string.player_status_no_music);
        }
    }

    /**
     * Locates the file the user picked in the freshly scanned library and
     * makes it the current track (playback starts there). One-shot: the
     * marker is consumed even when the file cannot be found.
     */
    private boolean applyStartFile() {
        String start = settingsRepository.getPlayerStartFile();
        if (start == null || start.isEmpty()) {
            return false;
        }
        settingsRepository.setPlayerStartFile(null);
        File wanted = new File(start);
        for (int f = 0; f < folders.size(); f++) {
            List<MusicLibrary.Track> tracks = folders.get(f).tracks;
            for (int t = 0; t < tracks.size(); t++) {
                MusicLibrary.Track track = tracks.get(t);
                if (track.file != null
                        && track.file.getAbsolutePath().equals(wanted.getAbsolutePath())) {
                    folderIdx = f;
                    trackIdx = t;
                    // Explicitly picked: play from the beginning, not from a
                    // position remembered for this file.
                    settingsRepository.setPlayerLastPositionMs(0);
                    return true;
                }
            }
        }
        // Path-based match failed (different mount naming?): fall back to the
        // file name.
        String wantedName = wanted.getName();
        for (int f = 0; f < folders.size(); f++) {
            List<MusicLibrary.Track> tracks = folders.get(f).tracks;
            for (int t = 0; t < tracks.size(); t++) {
                if (tracks.get(t).name.equalsIgnoreCase(wantedName)) {
                    folderIdx = f;
                    trackIdx = t;
                    settingsRepository.setPlayerLastPositionMs(0);
                    return true;
                }
            }
        }
        return false;
    }

    private Uri parseTreeUri(String saved) {
        if (saved == null || saved.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(saved);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Finds the remembered folder/track in the freshly scanned library. */
    private void restoreSavedSelection() {
        String savedFolder = settingsRepository.getPlayerLastFolder();
        String savedTrack = settingsRepository.getPlayerLastTrack();
        if (savedFolder == null || savedTrack == null) {
            return;
        }
        for (int f = 0; f < folders.size(); f++) {
            if (!folders.get(f).name.equalsIgnoreCase(savedFolder)) {
                continue;
            }
            List<MusicLibrary.Track> tracks = folders.get(f).tracks;
            for (int t = 0; t < tracks.size(); t++) {
                if (tracks.get(t).name.equalsIgnoreCase(savedTrack)) {
                    folderIdx = f;
                    trackIdx = t;
                    return;
                }
            }
        }
    }

    private void playOrResume() {
        ensureLibrary();
        if (folders.isEmpty()) {
            syncState(true);
            return;
        }
        if (dataSourceSet) {
            if (!requestFocus()) {
                return;
            }
            player.start();
            playing = true;
            pausedByHeadset = false;
            resumeOnFocusGain = false;
            syncState(true);
        } else {
            doPlay(folderIdx, trackIdx, 0);
        }
    }

    /** Plays folder f / track t, skipping broken files by walking forward. */
    private void doPlay(int f, int t, int attempts) {
        ensureLibrary();
        if (folders.isEmpty()) {
            syncState(true);
            return;
        }
        int totalTracks = totalTrackCount();
        if (attempts > totalTracks) {
            error = getString(R.string.player_file_error);
            playing = false;
            syncState(true);
            return;
        }

        f = clampFolder(f);
        t = clampTrack(f, t);
        MusicLibrary.Track track = folders.get(f).tracks.get(t);
        try {
            player.reset();
            dataSourceSet = false;
            setDataSource(track);
            player.prepare();
        } catch (Exception e) {
            // Broken or unreadable file: skip to the next one.
            int[] next = nextSequential(f, t, 1);
            doPlay(next[0], next[1], attempts + 1);
            return;
        }
        dataSourceSet = true;
        folderIdx = f;
        trackIdx = t;
        error = null;
        // A new track ends any running -5s loop.
        stopLoop();
        if (!requestFocus()) {
            playing = false;
            syncState(true);
            return;
        }
        player.start();
        playing = true;
        pausedByHeadset = false;
        resumeOnFocusGain = false;
        ducked = false;

        // Remember this selection and resume where it was left off when the
        // same track is replayed (e.g. after process death or re-selection).
        boolean sameAsSaved =
                track.name.equalsIgnoreCase(settingsRepository.getPlayerLastTrack())
                && folders.get(f).name.equalsIgnoreCase(settingsRepository.getPlayerLastFolder());
        int resumeMs = sameAsSaved ? settingsRepository.getPlayerLastPositionMs() : 0;
        if (resumeMs > 0) {
            int duration = player.getDuration();
            if (duration <= 0 || resumeMs < duration - 1000) {
                player.seekTo(resumeMs);
            }
        }
        settingsRepository.setPlayerLastSelection(folders.get(f).name, track.name);
        savePosition();
        syncState(true);
    }

    /** Sets the MediaPlayer source: a plain file path or a SAF document. */
    private void setDataSource(MusicLibrary.Track track) throws Exception {
        if (track.file != null) {
            player.setDataSource(track.file.getAbsolutePath());
            return;
        }
        AssetFileDescriptor afd = getContentResolver()
                .openAssetFileDescriptor(track.uri, "r");
        if (afd == null) {
            throw new java.io.IOException("Cannot open " + track.uri);
        }
        try {
            long length = afd.getLength();
            if (length > 0) {
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), length);
            } else {
                player.setDataSource(afd.getFileDescriptor());
            }
        } finally {
            afd.close();
        }
    }

    /** Persists the current playback position (used for resuming). */
    private void savePosition() {
        if (!dataSourceSet) {
            return;
        }
        try {
            settingsRepository.setPlayerLastPositionMs(Math.max(0, player.getCurrentPosition()));
        } catch (RuntimeException ignored) {
        }
    }

    private void advanceTrack(int direction, int attempts) {
        ensureLibrary();
        if (folders.isEmpty()) {
            syncState(true);
            return;
        }
        if (random && direction > 0) {
            // Random mode: a random file of the current folder (folders only
            // change when the user switches them explicitly).
            jumpToRandomTrackInFolder(attempts);
            return;
        }
        int[] next = nextSequential(folderIdx, trackIdx, direction);
        doPlay(next[0], next[1], attempts);
    }

    private void jumpToRandomTrackInFolder(int attempts) {
        List<MusicLibrary.Track> tracks = folders.get(clampFolder(folderIdx)).tracks;
        if (tracks.size() <= 1) {
            doPlay(folderIdx, trackIdx, attempts);
            return;
        }
        int target = trackIdx;
        while (target == trackIdx) {
            target = rng.nextInt(tracks.size());
        }
        doPlay(folderIdx, target, attempts);
    }

    /**
     * Folder switching works on every level and is never a dead button. From
     * a folder like "Rock/live" it goes to the next subfolder of "Rock"
     * (siblings). When the current level has nothing to switch to (no
     * sibling folders with music), it falls back to cycling through ALL
     * folders of the library \u2014 jumping somewhere is better than doing
     * nothing. Folders are walked in order (never randomized); in random
     * mode the target folder starts at a random file instead of the first.
     */
    private void switchFolder(int direction) {
        ensureLibrary();
        if (folders.isEmpty()) {
            syncState(true);
            return;
        }
        folderIdx = clampFolder(folderIdx);
        trackIdx = clampTrack(folderIdx, trackIdx);
        String currentParent = parentPrefix(folders.get(folderIdx).name);
        List<Integer> level = folderIndicesAtLevel(currentParent);
        if (level.size() <= 1 || !level.contains(folderIdx)) {
            // No siblings at this level (or stale index): cycle the whole
            // library instead of doing nothing.
            level = new ArrayList<>();
            for (int i = 0; i < folders.size(); i++) {
                level.add(i);
            }
        }
        if (level.size() <= 1) {
            // Single-folder library: nowhere to switch.
            syncState(true);
            return;
        }
        int pos = level.indexOf(folderIdx);
        if (pos < 0) {
            pos = 0;
        }
        int next = level.get((pos + direction + level.size()) % level.size());
        int startTrack = random ? randomTrackIndex(next) : 0;
        doPlay(next, startTrack, 0);
    }

    /** A random track index of the given folder. */
    private int randomTrackIndex(int folder) {
        int size = folders.get(clampFolder(folder)).tracks.size();
        if (size <= 0) {
            return 0;
        }
        return rng.nextInt(size);
    }

    /** Indices of folders whose display name sits directly under "parent" ("" = top level). */
    private List<Integer> folderIndicesAtLevel(String parent) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < folders.size(); i++) {
            if (parentPrefix(folders.get(i).name).equals(parent)) {
                result.add(i);
            }
        }
        return result;
    }

    /** Parent prefix of a folder display name: "Rock/live" -> "Rock", "Rock" -> "". */
    private static String parentPrefix(String folderName) {
        if (folderName == null) {
            return "";
        }
        int slash = folderName.lastIndexOf('/');
        return slash < 0 ? "" : folderName.substring(0, slash);
    }

    private void rewind5s() {
        if (!dataSourceSet) {
            return;
        }
        int position = player.getCurrentPosition();
        player.seekTo(Math.max(0, position - loopSeconds * 1000));
        savePosition();
        syncState(false);
    }

    /** Relative seek (positive = forward), used by the swipe-to-seek gesture. */
    private void seekRelative(int ms) {
        if (!dataSourceSet || ms == 0) {
            return;
        }
        try {
            int position = player.getCurrentPosition();
            int target = Math.max(0, Math.min(position + ms,
                    Math.max(0, player.getDuration() - 250)));
            player.seekTo(target);
            savePosition();
            syncState(false);
        } catch (RuntimeException ignored) {
        }
    }

    /** Absolute seek in ms, used by the seekbar (works while paused too). */
    private void seekToAbsolute(int ms) {
        if (!dataSourceSet) {
            return;
        }
        try {
            int duration = player.getDuration();
            int target = Math.max(0, Math.min(ms,
                    Math.max(0, duration - 250)));
            player.seekTo(target);
            savePosition();
            syncState(true);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The \u2212N s button behavior: press once to loop the previous N seconds
     * (the segment keeps repeating), press again to release the loop.
     */
    private void toggleLoop5s() {
        if (!dataSourceSet) {
            return;
        }
        if (loopActive) {
            stopLoop();
        } else {
            try {
                loopStartMs = Math.max(0, player.getCurrentPosition() - loopSeconds * 1000);
            } catch (RuntimeException e) {
                loopStartMs = 0;
            }
            loopActive = true;
            mainHandler.postDelayed(loopRunnable, 250L);
        }
        savePosition();
        syncState(true);
    }

    private void stopLoop() {
        loopActive = false;
        mainHandler.removeCallbacks(loopRunnable);
    }

    private void pause(boolean byHeadset) {
        if (dataSourceSet && playing) {
            savePosition();
            player.pause();
            playing = false;
        }
        if (byHeadset) {
            pausedByHeadset = true;
        }
        syncState(true);
    }

    // ------------------------------------------------------------------
    // Audio focus
    // ------------------------------------------------------------------

    private boolean requestFocus() {
        if (focusHeld) {
            return true;
        }
        int result = audioManager.requestAudioFocus(focusRequest);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }

    private void onAudioFocusChanged(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                executor.execute(() -> {
                    pause(false);
                    resumeOnFocusGain = false;
                });
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                executor.execute(() -> {
                    boolean wasPlaying = playing;
                    pause(false);
                    resumeOnFocusGain = wasPlaying;
                });
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                executor.execute(() -> {
                    if (dataSourceSet) {
                        player.setVolume(0.2f, 0.2f);
                        ducked = true;
                    }
                });
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                executor.execute(() -> {
                    if (ducked) {
                        player.setVolume(1f, 1f);
                        ducked = false;
                    } else if (resumeOnFocusGain && !playing) {
                        resumeOnFocusGain = false;
                        playOrResume();
                    }
                });
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Headphones plug / unplug
    // ------------------------------------------------------------------

    private final AudioDeviceCallbackAdapter deviceCallback = new AudioDeviceCallbackAdapter() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (containsHeadset(addedDevices)) {
                executor.execute(() -> {
                    if (pausedByHeadset) {
                        pausedByHeadset = false;
                        playOrResume();
                    }
                });
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (containsHeadset(removedDevices)) {
                executor.execute(() -> {
                    if (playing) {
                        pause(true);
                    }
                });
            }
        }
    };

    /** Pauses when the output falls back to the speaker (wired unplug, BT off). */
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                executor.execute(() -> {
                    if (playing) {
                        pause(true);
                    }
                });
            }
        }
    };

    private static boolean containsHeadset(AudioDeviceInfo[] devices) {
        if (devices == null) {
            return false;
        }
        for (AudioDeviceInfo device : devices) {
            if (device == null) {
                continue;
            }
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }

    private static class AudioDeviceCallbackAdapter extends android.media.AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
        }
    }

    // ------------------------------------------------------------------
    // State / notification / media session
    // ------------------------------------------------------------------

    /** Publishes the current state to the UI snapshot, media session and (optionally) notification. */
    private void syncState(boolean updateNotification) {
        Snapshot snapshot = buildSnapshot();
        CURRENT = snapshot;
        updateSession(snapshot);
        if (updateNotification) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(snapshot));
            }
        }
    }

    private Snapshot buildSnapshot() {
        boolean ready = scanned && !folders.isEmpty();
        String fileName = null;
        String folderName = null;
        int folderCount = folders.size();
        int trackCount = 0;
        int positionMs = 0;
        int durationMs = 0;

        if (ready) {
            folderIdx = clampFolder(folderIdx);
            trackIdx = clampTrack(folderIdx, trackIdx);
            MusicLibrary.Track current = folders.get(folderIdx).tracks.get(trackIdx);
            fileName = current.name;
            folderName = folders.get(folderIdx).name;
            trackCount = folders.get(folderIdx).tracks.size();
        }
        if (dataSourceSet && player != null) {
            try {
                positionMs = Math.max(0, player.getCurrentPosition());
                durationMs = Math.max(0, player.getDuration());
            } catch (RuntimeException ignored) {
            }
        }
        return new Snapshot(ready, playing, random, scanning, loopActive, customFolder, error,
                fileName, folderName, folderIdx, folderCount, trackIdx, trackCount,
                positionMs, durationMs, loopSeconds);
    }

    private void updateSession(Snapshot snapshot) {
        if (session == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_REWIND;
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(actions)
                .setState(snapshot.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        snapshot.positionMs, snapshot.playing ? 1f : 0f)
                .build();
        session.setPlaybackState(playbackState);

        android.media.MediaMetadata.Builder metadata = new android.media.MediaMetadata.Builder();
        if (snapshot.fileName != null) {
            metadata.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, snapshot.fileName);
        }
        if (snapshot.folderName != null) {
            metadata.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, snapshot.folderName);
            metadata.putString(android.media.MediaMetadata.METADATA_KEY_ALBUM,
                    (snapshot.trackIndex + 1) + "/" + snapshot.trackCount);
        }
        metadata.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs);
        session.setMetadata(metadata.build());
    }

    private Notification buildNotification(Snapshot snapshot) {
        String title = snapshot.ready && snapshot.fileName != null
                ? snapshot.fileName : getString(R.string.app_name);
        String text;
        if (snapshot.scanning) {
            text = getString(R.string.player_status_scanning);
        } else if (snapshot.error != null) {
            text = snapshot.error;
        } else if (snapshot.ready) {
            text = snapshot.folderName + " \u00B7 " + (snapshot.trackIndex + 1) + "/" + snapshot.trackCount;
        } else {
            text = getString(R.string.player_status_idle);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(EXTRA_OPEN_PLAYER, true);
        // Distinct request code: the steps notification uses another one.
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 11, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3)
                .setMediaSession(session.getSessionToken());

        // Expanded view: custom layout with a progress bar (scrollbar) and
        // all five buttons including folder prev/next. Android 9's MediaStyle
        // template has no progress bar, so the expanded state uses RemoteViews.
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notif_player);
        views.setTextViewText(R.id.notif_title, title);
        views.setTextViewText(R.id.notif_text, text);
        boolean showProgress = snapshot.ready && snapshot.durationMs > 0;
        views.setViewVisibility(R.id.notif_progress,
                showProgress ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showProgress) {
            views.setProgressBar(R.id.notif_progress, snapshot.durationMs,
                    Math.min(snapshot.positionMs, snapshot.durationMs), false);
        }
        views.setOnClickPendingIntent(R.id.notif_btn_folder_prev,
                commandIntent(ACTION_PREV_FOLDER, 0));
        views.setOnClickPendingIntent(R.id.notif_btn_prev, commandIntent(ACTION_PREV, 2));
        views.setOnClickPendingIntent(R.id.notif_btn_play, commandIntent(ACTION_TOGGLE, 3));
        views.setOnClickPendingIntent(R.id.notif_btn_next, commandIntent(ACTION_NEXT, 4));
        views.setOnClickPendingIntent(R.id.notif_btn_folder_next,
                commandIntent(ACTION_NEXT_FOLDER, 5));
        views.setImageViewResource(R.id.notif_btn_play,
                snapshot.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        // Five slots: folder prev, track prev, play/pause, track next, folder next.
        // The -5s loop stays in-app (and on the MediaSession rewind action) —
        // notifications allow at most five actions.
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                // Always ongoing: swiping the paused notification away used to
                // demote (and eventually stop) the service after a long pause.
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setStyle(style)
                .setCustomBigContentView(views)
                .addAction(android.R.drawable.ic_menu_revert,
                        getString(R.string.player_btn_folder_prev), commandIntent(ACTION_PREV_FOLDER, 0))
                .addAction(android.R.drawable.ic_media_previous,
                        getString(R.string.player_btn_prev), commandIntent(ACTION_PREV, 2))
                .addAction(snapshot.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        snapshot.playing ? getString(R.string.player_btn_pause) : getString(R.string.player_btn_play),
                        commandIntent(ACTION_TOGGLE, 3))
                .addAction(android.R.drawable.ic_media_next,
                        getString(R.string.player_btn_next), commandIntent(ACTION_NEXT, 4))
                .addAction(android.R.drawable.ic_menu_more,
                        getString(R.string.player_btn_folder_next), commandIntent(ACTION_NEXT_FOLDER, 5))
                .build();
    }

    private PendingIntent commandIntent(String action, int requestCode) {
        Intent intent = new Intent(this, FolderPlayerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startAsForeground() {
        Notification notification = buildNotification(buildSnapshot());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_player), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    // ------------------------------------------------------------------
    // Index helpers
    // ------------------------------------------------------------------

    private int totalTrackCount() {
        int total = 0;
        for (MusicLibrary.Folder folder : folders) {
            total += folder.tracks.size();
        }
        return total;
    }

    private int clampFolder(int f) {
        if (folders.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(f, folders.size() - 1));
    }

    private int clampTrack(int f, int t) {
        if (folders.isEmpty()) {
            return 0;
        }
        int size = folders.get(clampFolder(f)).tracks.size();
        if (size == 0) {
            return 0;
        }
        return Math.max(0, Math.min(t, size - 1));
    }

    /** Sequential next/prev across folder borders, wrapping around the library. */
    private int[] nextSequential(int f, int t, int direction) {
        if (folders.isEmpty()) {
            return new int[]{0, 0};
        }
        int folder = f;
        int track = t + direction;
        if (direction > 0) {
            while (folder < folders.size() && track >= folders.get(folder).tracks.size()) {
                folder++;
                track = 0;
            }
            if (folder >= folders.size()) {
                folder = 0;
                track = 0;
            }
        } else {
            while (folder >= 0 && track < 0) {
                folder--;
                track = folder >= 0 ? folders.get(folder).tracks.size() - 1 : -1;
            }
            if (folder < 0) {
                folder = folders.size() - 1;
                track = folders.get(folder).tracks.size() - 1;
            }
        }
        return new int[]{folder, Math.max(0, track)};
    }
}
