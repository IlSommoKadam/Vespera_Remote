package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Daytime pull of Vespera {@code /USER} photos onto the mounted USB HD.
 * After all copies are size-verified, matching remotes are deleted. Progress includes
 * bytes transferred and an ETA based on measured throughput.
 */
public final class PhotoSyncEngine {
    private static final String TAG = "VesperaSync";
    static final String REMOTE_ROOT = "/USER";
    static final String HOST = "10.0.0.1";
    static final int PORT = 21;

    public interface Listener {
        void onProgress(SyncProgress progress);
    }

    public static final class FileLine {
        public final String folder;
        public final String name;
        public final long bytes;
        public final String kind;

        FileLine(String folder, String name, long bytes, String kind) {
            this.folder = folder == null || folder.isEmpty() ? "USER" : folder;
            this.name = name == null ? "" : name;
            this.bytes = Math.max(0, bytes);
            this.kind = kind == null ? "copied" : kind;
        }
    }

    public static final class Result {
        public final int downloaded;
        public final int skipped;
        public final int deleted;
        public final int failed;
        public final long bytes;
        public final String error;
        public final List<String> folders;
        public final List<FileLine> files;
        public final String remoteRoot;

        Result(int downloaded, int skipped, int deleted, int failed, long bytes, String error) {
            this(downloaded, skipped, deleted, failed, bytes, error,
                    Collections.emptyList(), Collections.emptyList(), REMOTE_ROOT);
        }

        Result(int downloaded, int skipped, int deleted, int failed, long bytes, String error,
               List<String> folders, List<FileLine> files, String remoteRoot) {
            this.downloaded = downloaded;
            this.skipped = skipped;
            this.deleted = deleted;
            this.failed = failed;
            this.bytes = bytes;
            this.error = error;
            this.folders = folders == null ? Collections.emptyList() : folders;
            this.files = files == null ? Collections.emptyList() : files;
            this.remoteRoot = remoteRoot == null ? REMOTE_ROOT : remoteRoot;
        }

        public static Result error(String message) {
            return new Result(0, 0, 0, 0, 0, message);
        }
    }

    private PhotoSyncEngine() {}

    public static Result sync(Network network, File localRoot, Listener listener) {
        return sync(network, localRoot, listener, null);
    }

    public static Result sync(Network network, File localRoot, Listener listener, BooleanSupplier pause) {
        if (localRoot == null || !localRoot.isDirectory()) {
            return Result.error("hd-unmounted");
        }
        File userDir = new File(localRoot, "USER");
        if (!userDir.exists() && !userDir.mkdirs()) {
            return Result.error("local-user");
        }
        purgePartFiles(userDir);
        SyncProgress progress = new SyncProgress();
        progress.active = true;
        progress.phase = SyncProgress.PHASE_LIST;
        publish(listener, progress);
        SpeedTracker speed = new SpeedTracker();
        try (CommonsFtpClient ftp = new CommonsFtpClient(network, HOST)) {
            int port = FtpProbe.findVesperaControl(network, HOST);
            if (port <= 0) {
                return Result.error("FTP Vespera: nessuna porta in ascolto");
            }
            ftp.connect(port);
            String remoteRoot = ftp.resolveUserDir();
            List<CommonsFtpClient.Entry> remote = ftp.listRecursive(remoteRoot);
            List<CommonsFtpClient.Entry> photos = new ArrayList<>();
            long totalBytes = 0;
            for (CommonsFtpClient.Entry entry : remote) {
                if (!entry.directory && isPhoto(entry.name)) {
                    photos.add(entry);
                    long size = entry.size > 0 ? entry.size : 0;
                    totalBytes += size;
                }
            }
            progress.fileTotal = photos.size();
            progress.totalBytes = totalBytes;
            progress.detail = photos.isEmpty() ? "0" : String.valueOf(photos.size());
            publish(listener, progress);

            int downloaded = 0;
            int skipped = 0;
            int deleted = 0;
            int failed = 0;
            long bytes = 0;
            Set<String> dirs = new HashSet<>();
            List<CommonsFtpClient.Entry> pendingDelete = new ArrayList<>();
            java.util.LinkedHashMap<String, FileLine> tracked = new java.util.LinkedHashMap<>();

            progress.phase = SyncProgress.PHASE_DOWNLOAD;
            publish(listener, progress);
            int index = 0;
            for (CommonsFtpClient.Entry entry : photos) {
                if (isPaused(pause)) return pauseResult(progress, listener);
                index++;
                String relative = relativeUserPath(entry.path);
                File local = new File(userDir, relative);
                dirs.add(parentFtpPath(entry.path));
                long remoteSize = entry.size > 0 ? entry.size : ftp.sizeOf(entry.path);
                progress.fileIndex = index;
                progress.fileName = entry.name;
                progress.fileSize = Math.max(0, remoteSize);
                progress.fileBytes = 0;
                progress.copied = downloaded;
                progress.skipped = skipped;
                progress.deleted = deleted;
                progress.failed = failed;
                publish(listener, progress);

                if (local.exists() && local.isFile() && remoteSize > 0 && local.length() == remoteSize) {
                    skipped++;
                    progress.skipped = skipped;
                    if (remoteSize > 0) progress.doneBytes += remoteSize;
                    refreshEta(progress, speed);
                    pendingDelete.add(entry);
                    tracked.put(entry.path, fileLine(entry, remoteSize, "skipped"));
                    publish(listener, progress);
                    continue;
                }
                if (remoteSize > 0) {
                    File parent = local.getParentFile();
                    long free = parent != null ? parent.getFreeSpace() : localRoot.getFreeSpace();
                    if (free > 0 && free < remoteSize + 1_048_576L) {
                        failed++;
                        progress.failed = failed;
                        tracked.put(entry.path, fileLine(entry, remoteSize, "failed"));
                        Log.w(TAG, "not enough space for " + entry.path);
                        continue;
                    }
                }
                try {
                    speed.resetFile();
                    final long already = progress.doneBytes;
                    ftp.retrieve(entry.path, local, transferred -> {
                        if (isPaused(pause)) throw new IOException("paused");
                        progress.fileBytes = transferred;
                        progress.doneBytes = already + transferred;
                        speed.sample(transferred);
                        refreshEta(progress, speed);
                        publish(listener, progress);
                    });
                    long expect = remoteSize > 0 ? remoteSize : local.length();
                    if (!local.isFile() || local.length() != expect) {
                        failed++;
                        progress.failed = failed;
                        tracked.put(entry.path, fileLine(entry, expect, "failed"));
                        Log.w(TAG, "size mismatch " + entry.path + " local="
                                + (local.exists() ? local.length() : -1) + " remote=" + expect);
                        if (local.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            local.delete();
                        }
                        continue;
                    }
                    downloaded++;
                    bytes += local.length();
                    progress.copied = downloaded;
                    progress.fileBytes = local.length();
                    if (remoteSize <= 0) {
                        progress.doneBytes = already + local.length();
                        progress.totalBytes += local.length();
                    } else {
                        progress.doneBytes = already + local.length();
                    }
                    refreshEta(progress, speed);
                    pendingDelete.add(entry);
                    tracked.put(entry.path, fileLine(entry, local.length(), "copied"));
                    publish(listener, progress);
                } catch (IOException failureEx) {
                    if (isPaused(pause) || "paused".equals(failureEx.getMessage())) {
                        return pauseResult(progress, listener);
                    }
                    failed++;
                    progress.failed = failed;
                    tracked.put(entry.path, fileLine(entry, remoteSize, "failed"));
                    Log.w(TAG, "sync " + entry.path, failureEx);
                }
            }

            progress.phase = SyncProgress.PHASE_VERIFY;
            progress.fileTotal = pendingDelete.size();
            List<CommonsFtpClient.Entry> confirmed = new ArrayList<>();
            index = 0;
            for (CommonsFtpClient.Entry entry : pendingDelete) {
                if (isPaused(pause)) return pauseResult(progress, listener);
                index++;
                File local = new File(userDir, relativeUserPath(entry.path));
                long remoteSize = entry.size > 0 ? entry.size : ftp.sizeOf(entry.path);
                progress.fileIndex = index;
                progress.fileName = entry.name;
                progress.fileSize = Math.max(0, remoteSize);
                progress.fileBytes = local.isFile() ? local.length() : 0;
                publish(listener, progress);
                if (local.isFile() && (remoteSize <= 0 || local.length() == remoteSize)) {
                    confirmed.add(entry);
                } else {
                    failed++;
                    progress.failed = failed;
                    tracked.put(entry.path, fileLine(entry, remoteSize, "failed"));
                    Log.w(TAG, "verify fail " + entry.path + " local="
                            + (local.exists() ? local.length() : -1) + " remote=" + remoteSize);
                    if (local.exists() && remoteSize > 0 && local.length() != remoteSize) {
                        //noinspection ResultOfMethodCallIgnored
                        local.delete();
                    }
                }
            }

            progress.phase = SyncProgress.PHASE_DELETE;
            progress.fileTotal = confirmed.size();
            index = 0;
            for (CommonsFtpClient.Entry entry : confirmed) {
                if (isPaused(pause)) return pauseResult(progress, listener);
                index++;
                progress.fileIndex = index;
                progress.fileName = entry.name;
                progress.fileBytes = 0;
                progress.fileSize = 0;
                publish(listener, progress);
                if (deleteRemote(ftp, entry.path)) deleted++;
                progress.deleted = deleted;
                publish(listener, progress);
            }

            List<String> dirList = new ArrayList<>(dirs);
            Collections.sort(dirList, Comparator.reverseOrder());
            for (String dir : dirList) {
                if (dir == null || "/".equals(dir) || CommonsFtpClient.isUserDirName(dir)) {
                    continue;
                }
                ftp.removeDir(dir);
            }
            progress.copied = downloaded;
            progress.skipped = skipped;
            progress.deleted = deleted;
            progress.failed = failed;
            progress.phase = SyncProgress.PHASE_DONE;
            progress.etaMs = 0;
            progress.fileName = "";
            List<FileLine> fileLines = new ArrayList<>(tracked.values());
            List<String> folderNames = folderNames(fileLines);
            publish(listener, progress);
            return new Result(downloaded, skipped, deleted, failed, bytes, null,
                    folderNames, fileLines, remoteRoot);
        } catch (IOException failure) {
            Log.w(TAG, "sync failed", failure);
            if (isPaused(pause) || "paused".equals(failure.getMessage())) {
                return pauseResult(progress, listener);
            }
            progress.phase = SyncProgress.PHASE_ERROR;
            progress.detail = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            publish(listener, progress);
            if ("missing-user".equals(failure.getMessage())) return Result.error("missing-user");
            return Result.error(failure.getMessage() != null
                    ? failure.getMessage() : failure.getClass().getSimpleName());
        } finally {
            progress.active = false;
        }
    }

    private static boolean isPaused(BooleanSupplier pause) {
        try {
            return pause != null && pause.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Result pauseResult(SyncProgress progress, Listener listener) {
        progress.phase = SyncProgress.PHASE_PAUSED;
        progress.active = false;
        publish(listener, progress);
        return Result.error("paused");
    }

    static boolean isPhoto(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith(".")) return false;
        return n.endsWith(".fits") || n.endsWith(".fit") || n.endsWith(".fts")
                || n.endsWith(".tif") || n.endsWith(".tiff")
                || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".raw")
                || n.endsWith(".cr2") || n.endsWith(".nef");
    }

    static int countLocalPhotos(File localRoot) {
        File user = new File(localRoot, "USER");
        return countPhotos(user);
    }

    static boolean hasIncomplete(File localRoot) {
        if (localRoot == null) return false;
        return hasPartFiles(new File(localRoot, "USER"));
    }

    static int purgePartFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                n += purgePartFiles(child);
            } else if (isPartFile(child.getName())) {
                if (child.delete()) n++;
            }
        }
        return n;
    }

    private static boolean hasPartFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory()) {
                if (hasPartFiles(child)) return true;
            } else if (isPartFile(child.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPartFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".part") || n.endsWith(".partial");
    }

    private static int countPhotos(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File child : children) {
            if (child.isDirectory()) n += countPhotos(child);
            else if (isPhoto(child.getName())) n++;
        }
        return n;
    }

    private static boolean deleteRemote(CommonsFtpClient ftp, String path) {
        try {
            ftp.deleteFile(path);
            return true;
        } catch (IOException failure) {
            Log.w(TAG, "delete " + path, failure);
            return false;
        }
    }

    private static FileLine fileLine(CommonsFtpClient.Entry entry, long size, String kind) {
        String relative = relativeUserPath(entry.path);
        String folder = parentFolder(relative);
        return new FileLine(folder, entry.name, size, kind);
    }

    private static String parentFolder(String relative) {
        if (relative == null || relative.isEmpty()) return "USER";
        int slash = relative.lastIndexOf('/');
        if (slash <= 0) return "USER";
        return relative.substring(0, slash);
    }

    private static List<String> folderNames(List<FileLine> files) {
        List<String> names = new ArrayList<>();
        for (FileLine line : files) {
            if (line.folder != null && !names.contains(line.folder)) names.add(line.folder);
        }
        Collections.sort(names);
        return names;
    }

    private static String relativeUserPath(String ftpPath) {
        String path = ftpPath.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.length() >= 4 && path.regionMatches(true, 0, "user", 0, 4)
                && (path.length() == 4 || path.charAt(4) == '/')) {
            return path.length() == 4 ? "" : path.substring(5);
        }
        return path;
    }

    private static String parentFtpPath(String ftpPath) {
        String path = ftpPath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "/";
        return path.substring(0, slash);
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0);
        }
        if (bytes >= 1_048_576L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static void publish(Listener listener, SyncProgress progress) {
        if (listener != null) listener.onProgress(progress);
    }

    private static void refreshEta(SyncProgress progress, SpeedTracker speed) {
        progress.speedBps = speed.bytesPerSec();
        long remaining = progress.totalBytes - progress.doneBytes;
        if (progress.speedBps > 0 && remaining > 0) {
            progress.etaMs = (remaining * 1000L) / progress.speedBps;
        } else if (remaining <= 0 && progress.totalBytes > 0) {
            progress.etaMs = 0;
        } else {
            progress.etaMs = -1;
        }
    }

    private static final class SpeedTracker {
        private long lastBytes;
        private long lastAt = android.os.SystemClock.elapsedRealtime();
        private long emaBps;

        void resetFile() {
            lastBytes = 0;
            lastAt = android.os.SystemClock.elapsedRealtime();
        }

        void sample(long fileBytes) {
            long now = android.os.SystemClock.elapsedRealtime();
            long dt = now - lastAt;
            if (dt < 250) return;
            long delta = fileBytes - lastBytes;
            if (delta < 0) delta = fileBytes;
            long inst = (delta * 1000L) / Math.max(1, dt);
            if (emaBps <= 0) emaBps = inst;
            else emaBps = (emaBps * 3 + inst) / 4;
            lastBytes = fileBytes;
            lastAt = now;
        }

        long bytesPerSec() {
            return emaBps;
        }
    }
}
