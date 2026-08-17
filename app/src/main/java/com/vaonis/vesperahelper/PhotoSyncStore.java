package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

/** Last photo-sync run, daytime window and daytime interval. */
final class PhotoSyncStore {
    static final int DEFAULT_DAY_START = 7;
    static final int DEFAULT_DAY_END = 19;
    static final float DEFAULT_INTERVAL_HOURS = 2f;
    static final float MIN_INTERVAL_HOURS = 0.25f;
    static final float MAX_INTERVAL_HOURS = 12f;

    private static final String PREFS = "vespera_photo_sync";
    private static final String KEY_START = "day_start_hour";
    private static final String KEY_END = "day_end_hour";
    private static final String KEY_INTERVAL_HOURS = "interval_hours";
    private static final String KEY_LAST_AT = "last_at";
    private static final String KEY_LAST_ATTEMPT = "last_attempt_at";
    private static final String KEY_LAST_COPIED = "last_copied";
    private static final String KEY_LAST_SKIPPED = "last_skipped";
    private static final String KEY_LAST_DELETED = "last_deleted";
    private static final String KEY_LAST_BYTES = "last_bytes";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_PHOTOS_PATH = "photos_path";
    private static final String KEY_IN_PROGRESS = "in_progress";
    private static final String KEY_PAUSED = "paused";
    static final String MARKER_NAME = "sync.inprogress";

    private final SharedPreferences prefs;

    private PhotoSyncStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static PhotoSyncStore from(Context context) {
        return new PhotoSyncStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    int dayStartHour() { return prefs.getInt(KEY_START, DEFAULT_DAY_START); }
    int dayEndHour() { return prefs.getInt(KEY_END, DEFAULT_DAY_END); }

    float intervalHours() {
        float hours = prefs.getFloat(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS);
        return clampIntervalHours(hours);
    }

    void setIntervalHours(float hours) {
        prefs.edit().putFloat(KEY_INTERVAL_HOURS, clampIntervalHours(hours)).commit();
    }

    static float clampIntervalHours(float hours) {
        if (hours < MIN_INTERVAL_HOURS) return MIN_INTERVAL_HOURS;
        if (hours > MAX_INTERVAL_HOURS) return MAX_INTERVAL_HOURS;
        return hours;
    }

    static float parseIntervalHours(String raw) {
        if (raw == null) return DEFAULT_INTERVAL_HOURS;
        String text = raw.trim().toLowerCase(Locale.US).replace(',', '.');
        if (text.endsWith("h")) text = text.substring(0, text.length() - 1).trim();
        if (text.isEmpty()) return DEFAULT_INTERVAL_HOURS;
        try {
            return clampIntervalHours(Float.parseFloat(text));
        } catch (NumberFormatException ignored) {
            return DEFAULT_INTERVAL_HOURS;
        }
    }

    static String formatIntervalHours(float hours) {
        float clamped = clampIntervalHours(hours);
        if (Math.abs(clamped - Math.round(clamped)) < 0.001f) {
            return String.valueOf(Math.round(clamped));
        }
        return String.format(Locale.US, "%.2f", clamped).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    long intervalMs() {
        return Math.round(intervalHours() * 3_600_000L);
    }

    long lastAttemptAt() { return prefs.getLong(KEY_LAST_ATTEMPT, 0); }

    void recordAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).commit();
    }

    boolean isDaytime(long nowMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nowMs);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour >= dayStartHour() && hour < dayEndHour();
    }

    long nextAutoAt(long nowMs) {
        long last = Math.max(lastAt(), lastAttemptAt());
        long candidate = last <= 0 ? nowMs : last + intervalMs();
        if (candidate < nowMs) candidate = nowMs;
        return alignToDaytime(candidate);
    }

    boolean isAutoDue(long nowMs) {
        if (paused()) return false;
        if (!isDaytime(nowMs)) return false;
        return nowMs >= nextAutoAt(nowMs);
    }

    long alignToDaytime(long timeMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int start = dayStartHour();
        int end = dayEndHour();
        if (hour >= start && hour < end) return timeMs;
        if (hour >= end) calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, start);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    long lastAt() { return prefs.getLong(KEY_LAST_AT, 0); }
    int lastCopied() { return prefs.getInt(KEY_LAST_COPIED, 0); }
    int lastSkipped() { return prefs.getInt(KEY_LAST_SKIPPED, 0); }
    int lastDeleted() { return prefs.getInt(KEY_LAST_DELETED, 0); }
    long lastBytes() { return prefs.getLong(KEY_LAST_BYTES, 0); }
    String lastError() { return prefs.getString(KEY_LAST_ERROR, ""); }
    boolean lastOk() { return prefs.getBoolean(KEY_LAST_OK, false); }
    String photosPath() { return prefs.getString(KEY_PHOTOS_PATH, ""); }
    boolean inProgress() { return prefs.getBoolean(KEY_IN_PROGRESS, false); }
    boolean paused() { return prefs.getBoolean(KEY_PAUSED, false); }

    boolean shouldResume(Context context) {
        if (paused()) return false;
        if (inProgress()) return true;
        File marker = markerFile(context);
        return marker != null && marker.isFile();
    }

    void setPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).commit();
    }

    void markInProgress(Context context) {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true).putBoolean(KEY_PAUSED, false).commit();
        writeMarker(context, true);
    }

    void markInterrupted(Context context) {
        if (!inProgress()) return;
        writeMarker(context, true);
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true).commit();
    }

    void clearInProgress(Context context) {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false).putBoolean(KEY_PAUSED, false).commit();
        writeMarker(context, false);
    }

    static File markerFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return null;
        return new File(dir, MARKER_NAME);
    }

    private static void writeMarker(Context context, boolean present) {
        File marker = markerFile(context);
        if (marker == null) return;
        try {
            if (present) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(marker, false);
                try {
                    String line = Long.toString(System.currentTimeMillis()) + "\n";
                    out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                //noinspection ResultOfMethodCallIgnored
                marker.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                marker.setWritable(true, false);
            } else if (marker.exists()) {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            }
        } catch (Exception ignored) {
        }
    }

    void setPhotosPath(String path) {
        prefs.edit().putString(KEY_PHOTOS_PATH, path == null ? "" : path).apply();
    }

    void recordSuccess(int copied, int skipped, int deleted, long bytes) {
        prefs.edit()
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putInt(KEY_LAST_COPIED, copied)
                .putInt(KEY_LAST_SKIPPED, skipped)
                .putInt(KEY_LAST_DELETED, deleted)
                .putLong(KEY_LAST_BYTES, bytes)
                .putString(KEY_LAST_ERROR, "")
                .putBoolean(KEY_LAST_OK, true)
                .apply();
    }

    void recordFailure(String error) {
        prefs.edit()
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, error == null ? "" : error)
                .putBoolean(KEY_LAST_OK, false)
                .apply();
    }
}
