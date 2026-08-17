package com.vaonis.vesperahelper;

import java.util.Locale;

/** Snapshot of a USER photo sync run (transfer / verify / delete). */
final class SyncProgress {
    static final String PHASE_LIST = "list";
    static final String PHASE_DOWNLOAD = "download";
    static final String PHASE_VERIFY = "verify";
    static final String PHASE_DELETE = "delete";
    static final String PHASE_DONE = "done";
    static final String PHASE_ERROR = "error";
    static final String PHASE_PAUSED = "paused";

    volatile boolean active;
    volatile String phase = PHASE_LIST;
    volatile String fileName = "";
    volatile String detail = "";
    volatile int fileIndex;
    volatile int fileTotal;
    volatile long fileBytes;
    volatile long fileSize;
    volatile long doneBytes;
    volatile long totalBytes;
    volatile long speedBps;
    volatile long etaMs = -1;
    volatile int copied;
    volatile int skipped;
    volatile int deleted;
    volatile int failed;

    int permille() {
        if (totalBytes > 0) {
            return (int) Math.max(0, Math.min(1000, (doneBytes * 1000) / totalBytes));
        }
        if (fileTotal > 0) {
            int done = Math.max(0, fileIndex - (PHASE_DOWNLOAD.equals(phase) ? 1 : 0));
            return Math.max(0, Math.min(1000, (done * 1000) / fileTotal));
        }
        return PHASE_DONE.equals(phase) ? 1000 : 0;
    }

    static String formatEta(long etaMs) {
        if (etaMs < 0) return "—";
        long sec = Math.max(0, (etaMs + 500) / 1000);
        if (sec < 60) return sec + " s";
        long min = sec / 60;
        sec = sec % 60;
        if (min < 60) return min + " min " + sec + " s";
        long hours = min / 60;
        min = min % 60;
        return hours + " h " + min + " min";
    }

    static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        return PhotoSyncEngine.formatBytes(bytesPerSec) + "/s";
    }

    String compactStatus() {
        String name = fileName == null || fileName.isEmpty() ? "" : fileName;
        if (PHASE_DONE.equals(phase) || PHASE_ERROR.equals(phase) || PHASE_PAUSED.equals(phase)) {
            return detail == null ? "" : detail;
        }
        String eta = formatEta(etaMs);
        if (fileTotal > 0 && !name.isEmpty()) {
            return fileIndex + "/" + fileTotal + " " + name + " · " + eta;
        }
        return detail == null ? name : detail;
    }

    static String phaseLabel(android.content.Context context, String phase) {
        if (phase == null) phase = PHASE_LIST;
        switch (phase) {
            case PHASE_DOWNLOAD:
                return context.getString(R.string.photo_sync_phase_download);
            case PHASE_VERIFY:
                return context.getString(R.string.photo_sync_phase_verify);
            case PHASE_DELETE:
                return context.getString(R.string.photo_sync_phase_delete);
            case PHASE_DONE:
                return context.getString(R.string.photo_sync_phase_done);
            case PHASE_ERROR:
                return context.getString(R.string.photo_sync_phase_error);
            case PHASE_PAUSED:
                return context.getString(R.string.photo_sync_phase_paused);
            case PHASE_LIST:
            default:
                return context.getString(R.string.photo_sync_phase_list);
        }
    }

    static String formatCount(long done, long total) {
        if (total > 0) {
            return PhotoSyncEngine.formatBytes(done) + " / " + PhotoSyncEngine.formatBytes(total);
        }
        if (done > 0) return PhotoSyncEngine.formatBytes(done);
        return "—";
    }

    @SuppressWarnings("unused")
    static String percentLabel(int permille) {
        return String.format(Locale.US, "%d%%", permille / 10);
    }
}
