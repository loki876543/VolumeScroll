package com.yeapguy.volumescroll;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the music library. Two sources are supported:
 *
 * 1. Default scan: the Music folders of internal storage and every SD card.
 * 2. A SAF folder tree picked with the system folder selector
 *    (ACTION_OPEN_DOCUMENT_TREE): the chosen folder is scanned recursively
 *    through DocumentsContract, so any location (including SD cards and
 *    Downloads) works without extra permissions.
 *
 * No metadata/tags are read — a "folder" is simply a directory and its
 * display name is the path relative to its root.
 */
public final class MusicLibrary {
    /** Safety cap so a huge library cannot make the scan endless. */
    public static final int MAX_TRACKS = 8000;
    private static final int MAX_DEPTH = 8;
    /** Secondary storage volumes look like /storage/1A2B-3C4D on Android 4.4+. */
    private static final Pattern REMOVABLE_VOLUME_PATTERN = Pattern.compile("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}");
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".wma"
    };
    /** Video files: only the audio track is played (no surface is attached). */
    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".3g2", ".ts", ".mts", ".flv", ".wmv", ".ogv"
    };

    private MusicLibrary() {
    }

    /** One playable audio file: either a plain file or a SAF document URI. */
    public static final class Track {
        public final File file;   // non-null for filesystem tracks
        public final Uri uri;     // non-null for SAF document tracks
        public final String name; // file name with extension

        Track(File file) {
            this.file = file;
            this.uri = null;
            this.name = file.getName();
        }

        Track(Uri uri, String name) {
            this.file = null;
            this.uri = uri;
            this.name = name;
        }
    }

    /** A folder of music: display name plus its audio files sorted by name. */
    public static final class Folder {
        public final String name;
        public final List<Track> tracks;

        Folder(String name, List<Track> tracks) {
            this.name = name;
            this.tracks = tracks;
        }
    }

    /**
     * Default scan of the Music folders on internal storage and SD cards.
     */
    public static List<Folder> scan() {
        List<File> roots = musicRoots();
        Map<String, List<Track>> tracksByDirectory = new LinkedHashMap<>();
        int total = 0;
        for (File root : roots) {
            total = collectAudio(root, root, tracksByDirectory, 0, total);
        }
        return buildFolders(tracksByDirectory, new DisplayNameResolver() {
            @Override
            public String resolve(String directoryPath) {
                return displayNameFor(directoryPath, roots);
            }
        });
    }

    /**
     * Scans one directory picked through the file selector (the folder that
     * contains the picked file). The folder itself and its subfolders become
     * player folders; names are shown relative to the picked folder.
     */
    public static List<Folder> scanDirectory(File pickedDir) {
        Map<String, List<Track>> tracksByDirectory = new LinkedHashMap<>();
        collectAudio(pickedDir, pickedDir, tracksByDirectory, 0, 0);
        final String rootPath = pickedDir.getAbsolutePath();
        final String rootName = pickedDir.getName().isEmpty() ? rootPath : pickedDir.getName();
        return buildFolders(tracksByDirectory, new DisplayNameResolver() {
            @Override
            public String resolve(String directoryPath) {
                if (directoryPath.equals(rootPath)) {
                    return rootName;
                }
                if (directoryPath.startsWith(rootPath + File.separator)) {
                    String relative = directoryPath.substring(rootPath.length() + 1)
                            .replace(File.separatorChar, '/');
                    return rootName + "/" + relative;
                }
                return new File(directoryPath).getName();
            }
        });
    }

    /**
     * Resolves a picked document URI to a real file on the filesystem, so the
     * folder that contains it can be scanned directly (READ_EXTERNAL_STORAGE
     * covers it on this API level). Works for the storage provider (document
     * ids are path-like: "primary:Music/song.mp3") and for the media provider
     * ("audio:123" is looked up in MediaStore). Returns null when the file is
     * not on local storage (e.g. a cloud document).
     */
    public static File resolveLocalFile(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (RuntimeException e) {
            return null;
        }
        if (docId == null || docId.isEmpty()) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String volume = docId.substring(0, colon);
        String path = docId.substring(colon + 1);

        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            File root = storageVolumeRoot(volume);
            if (root != null && !path.isEmpty()) {
                File resolved = new File(root, path);
                return resolved;
            }
            return null;
        }

        // Media provider: ids look like "audio:123" / "video:123".
        if (volume.equals("audio") || volume.equals("video")
                || volume.equals("image") || volume.equals("file")) {
            String numericId = path.contains(":") ? path.substring(path.lastIndexOf(':') + 1) : path;
            if (numericId.matches("[0-9]+")) {
                Cursor cursor = null;
                try {
                    cursor = context.getContentResolver().query(
                            android.provider.MediaStore.Files.getContentUri("external"),
                            new String[]{android.provider.MediaStore.MediaColumns.DATA},
                            "_id=?", new String[]{numericId}, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        String data = cursor.getString(0);
                        if (data != null && !data.isEmpty()) {
                            return new File(data);
                        }
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }
        return null;
    }

    /** Maps a storage volume name from a document id to its mount point. */
    private static File storageVolumeRoot(String volume) {
        if ("primary".equals(volume)) {
            try {
                return Environment.getExternalStorageDirectory();
            } catch (RuntimeException e) {
                return null;
            }
        }
        if ("emulated".equals(volume)) {
            return new File("/storage/emulated/0");
        }
        if (REMOVABLE_VOLUME_PATTERN.matcher(volume).matches()) {
            return new File("/storage", volume);
        }
        return null;
    }

    /**
     * Scans the folder tree the user picked with the system selector.
     * Subdirectories of the chosen folder become player folders; audio files
     * sitting directly in the chosen folder form its first folder.
     */
    public static List<Folder> scanTree(Context context, Uri treeUri) {
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        String resolved = lastPathSegment(rootDocId);
        if (resolved == null || resolved.isEmpty()) {
            resolved = "Music";
        }
        final String rootName = resolved;
        Map<String, List<Track>> tracksByDirectory = new LinkedHashMap<>();
        collectDocuments(context.getContentResolver(), treeUri, rootDocId, "", tracksByDirectory, 0, 0);
        return buildFolders(tracksByDirectory, new DisplayNameResolver() {
            @Override
            public String resolve(String relativePath) {
                return relativePath.isEmpty() ? rootName : rootName + "/" + relativePath;
            }
        });
    }

    private interface DisplayNameResolver {
        String resolve(String directoryPath);
    }

    private static List<Folder> buildFolders(Map<String, List<Track>> tracksByDirectory,
                                             DisplayNameResolver resolver) {
        List<Folder> folders = new ArrayList<>();
        for (Map.Entry<String, List<Track>> entry : tracksByDirectory.entrySet()) {
            List<Track> tracks = entry.getValue();
            if (tracks.isEmpty()) {
                continue;
            }
            Collections.sort(tracks, new Comparator<Track>() {
                @Override
                public int compare(Track a, Track b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            folders.add(new Folder(resolver.resolve(entry.getKey()), tracks));
        }
        Collections.sort(folders, new Comparator<Folder>() {
            @Override
            public int compare(Folder a, Folder b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return folders;
    }

    private static int collectAudio(File root, File dir, Map<String, List<Track>> out,
                                    int depth, int total) {
        if (total >= MAX_TRACKS || depth > MAX_DEPTH || dir == null) {
            return total;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return total;
        }

        List<Track> audioFiles = new ArrayList<>();
        List<File> subDirectories = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith(".")) {
                continue; // skip hidden files/folders
            }
            if (entry.isDirectory()) {
                subDirectories.add(entry);
            } else if (entry.isFile() && isPlayableFile(name)) {
                audioFiles.add(new Track(entry));
            }
        }

        if (!audioFiles.isEmpty()) {
            String key = dir.getAbsolutePath();
            List<Track> existing = out.get(key);
            if (existing == null) {
                existing = new ArrayList<>();
                out.put(key, existing);
            }
            existing.addAll(audioFiles);
            total += audioFiles.size();
        }

        for (File subDirectory : subDirectories) {
            total = collectAudio(root, subDirectory, out, depth + 1, total);
        }
        return total;
    }

    /**
     * Depth-first walk of a SAF document tree. relativePath is the path of
     * the current directory below the picked folder ("" for the folder
     * itself); audio documents are grouped by it.
     */
    private static int collectDocuments(android.content.ContentResolver resolver, Uri treeUri,
                                        String parentDocId, String relativePath,
                                        Map<String, List<Track>> out, int depth, int total) {
        if (total >= MAX_TRACKS || depth > MAX_DEPTH) {
            return total;
        }
        Uri childrenUri;
        Cursor cursor = null;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            cursor = resolver.query(childrenUri, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
        } catch (RuntimeException ignored) {
            cursor = null;
        }
        if (cursor == null) {
            return total;
        }

        List<String> subDocIds = new ArrayList<>();
        List<String> subPaths = new ArrayList<>();
        List<Track> audioFiles = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                if (total >= MAX_TRACKS) {
                    break;
                }
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (docId == null || name == null || name.startsWith(".")) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDocIds.add(docId);
                    subPaths.add(relativePath.isEmpty() ? name : relativePath + "/" + name);
                } else if (isPlayableFile(name)) {
                    audioFiles.add(new Track(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), name));
                    total++;
                }
            }
        } finally {
            cursor.close();
        }

        if (!audioFiles.isEmpty()) {
            List<Track> existing = out.get(relativePath);
            if (existing == null) {
                existing = new ArrayList<>();
                out.put(relativePath, existing);
            }
            existing.addAll(audioFiles);
        }

        for (int i = 0; i < subDocIds.size(); i++) {
            total = collectDocuments(resolver, treeUri, subDocIds.get(i), subPaths.get(i),
                    out, depth + 1, total);
        }
        return total;
    }

    /** Last segment of a tree document id like "primary:Music/Rock". */
    private static String lastPathSegment(String treeDocumentId) {
        if (treeDocumentId == null) {
            return null;
        }
        int colon = treeDocumentId.lastIndexOf(':');
        String path = colon >= 0 ? treeDocumentId.substring(colon + 1) : treeDocumentId;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** All Music roots found: internal storage first, then every SD card. */
    private static List<File> musicRoots() {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();

        try {
            File publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (publicMusic != null) {
                candidates.add(new File(publicMusic.getAbsolutePath()));
            }
        } catch (RuntimeException ignored) {
        }
        try {
            File external = Environment.getExternalStorageDirectory();
            if (external != null) {
                candidates.add(new File(external, "Music"));
            }
        } catch (RuntimeException ignored) {
        }

        File[] storageVolumes = new File("/storage").listFiles();
        if (storageVolumes != null) {
            for (File volume : storageVolumes) {
                if (!volume.isDirectory()) {
                    continue;
                }
                String name = volume.getName();
                if ("emulated".equals(name)) {
                    candidates.add(new File(volume, "0/Music"));
                } else if (REMOVABLE_VOLUME_PATTERN.matcher(name).matches()) {
                    candidates.add(new File(volume, "Music"));
                }
            }
        }

        // Legacy env var still populated by many Android 9 builds.
        String secondary = System.getenv("SECONDARY_STORAGE");
        if (secondary != null && !secondary.trim().isEmpty()) {
            for (String part : secondary.split(":")) {
                if (!part.trim().isEmpty()) {
                    candidates.add(new File(part.trim(), "Music"));
                }
            }
        }

        List<File> roots = new ArrayList<>();
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                roots.add(candidate);
            }
        }
        return roots;
    }

    private static String displayNameFor(String directoryPath, List<File> roots) {
        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            if (directoryPath.equals(rootPath)) {
                return rootLabel(root);
            }
            if (directoryPath.startsWith(rootPath + File.separator)) {
                String relative = directoryPath.substring(rootPath.length() + 1)
                        .replace(File.separatorChar, '/');
                return rootLabel(root) + "/" + relative;
            }
        }
        return new File(directoryPath).getName();
    }

    private static String rootLabel(File root) {
        String path = root.getAbsolutePath();
        if (path.contains("/storage/emulated")) {
            return "Music";
        }
        return "Music (SD)";
    }

    private static boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Audio or video file — both are playable (video plays audio-only). */
    private static boolean isPlayableFile(String name) {
        return isAudioFile(name) || isVideoFile(name);
    }

    private static boolean isVideoFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String extension : VIDEO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a library out of individually picked files (system file selector,
     * ACTION_OPEN_DOCUMENT). Files are grouped into folders by the directory
     * part of their SAF document id, so "Music/Rock/a.mp3" and "Music/Rock/b.mp3"
     * land in the same "Rock" folder. Video files are included and played
     * audio-only.
     */
    public static List<Folder> buildFromFiles(Context context, List<Uri> fileUris) {
        Map<String, List<Track>> tracksByDirectory = new LinkedHashMap<>();
        if (fileUris != null) {
            for (Uri uri : fileUris) {
                if (uri == null) {
                    continue;
                }
                String docId;
                try {
                    docId = DocumentsContract.getDocumentId(uri);
                } catch (RuntimeException e) {
                    docId = null;
                }
                if (docId == null || docId.isEmpty()) {
                    continue;
                }
                String name = lastSegment(docId);
                if (name == null || name.isEmpty() || !isPlayableFile(name)) {
                    continue;
                }
                String folderKey = parentPath(docId);
                List<Track> existing = tracksByDirectory.get(folderKey);
                if (existing == null) {
                    existing = new ArrayList<>();
                    tracksByDirectory.put(folderKey, existing);
                }
                existing.add(new Track(uri, name));
            }
        }
        return buildFolders(tracksByDirectory, new DisplayNameResolver() {
            @Override
            public String resolve(String directoryPath) {
                return directoryPath;
            }
        });
    }

    /** "primary:Music/Rock/song.mp3" -> "Music/Rock" (or "Picked files"). */
    private static String parentPath(String docId) {
        int colon = docId.indexOf(':');
        String path = colon >= 0 ? docId.substring(colon + 1) : docId;
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "Picked files";
        }
        String parent = path.substring(0, slash);
        return parent.isEmpty() ? "Picked files" : parent;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
