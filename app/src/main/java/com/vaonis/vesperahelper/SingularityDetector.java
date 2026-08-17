package com.vaonis.vesperahelper;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads Singularity instrument state via the rooted {@code vespera-netd.sh} daemon. */
final class SingularityDetector {
    private static final String TAG = "SingularityDetector";
    static final String ACK_FILE = "singularity.ack";
    private static final String CHECK_CMD = "check-singularity";
    private static final long POLL_MS = 200;
    private static final long TIMEOUT_MS = 10_000;

    enum Status {
        CONNECTED,
        DISCONNECTED,
        NOT_RUNNING,
        API_DOWN,
        NO_WIFI,
        DAEMON_MISSING,
        UNKNOWN
    }

    static final class Result {
        final Status status;
        final String detail;

        Result(Status status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail.trim();
        }

        boolean isConnected() {
            return status == Status.CONNECTED;
        }
    }

    private SingularityDetector() {}

    static Result check(Context context) {
        File dir = ackDir(context);
        if (dir == null) {
            return new Result(Status.UNKNOWN, "no-files-dir");
        }
        File ack = new File(dir, ACK_FILE);
        ensureWritableAck(ack);
        String previous = ack.exists() ? readFirstLine(ack) : null;
        long before = ack.exists() ? ack.lastModified() : 0L;
        if (!VesperaConnectionService.writeSingularityRequest(context, CHECK_CMD)) {
            return new Result(Status.DAEMON_MISSING, "net-req-write-failed");
        }
        String line = pollAck(ack, before, previous);
        if (line == null || line.isEmpty()) {
            return new Result(Status.DAEMON_MISSING, "no-daemon-ack");
        }
        Log.i(TAG, "ack=" + line);
        return parse(line);
    }

    /** Keep an app-owned ack inode so the root daemon can truncate in place. */
    private static void ensureWritableAck(File ack) {
        try {
            if (!ack.exists()) {
                //noinspection ResultOfMethodCallIgnored
                ack.createNewFile();
            }
            //noinspection ResultOfMethodCallIgnored
            ack.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            ack.setWritable(true, false);
        } catch (Exception ignored) {
            // Daemon publish_file remains the fallback.
        }
    }

    private static Result parse(String line) {
        String lower = line.toLowerCase(Locale.US);
        if (lower.startsWith("singularity-connected")) {
            return new Result(Status.CONNECTED, line);
        }
        if (lower.contains("not-running")) {
            return new Result(Status.NOT_RUNNING, line);
        }
        if (lower.contains("vespera-api-down")) {
            return new Result(Status.API_DOWN, line);
        }
        if (lower.contains("wifi-not-vespera")) {
            return new Result(Status.NO_WIFI, line);
        }
        if (lower.startsWith("singularity-disconnected")
                || lower.startsWith("singularity-missing")) {
            return new Result(Status.DISCONNECTED, line);
        }
        return new Result(Status.UNKNOWN, line);
    }

    private static String pollAck(File ack, long modifiedAfter, String previousLine) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (ack.exists()) {
                String line = readFirstLine(ack);
                if (line != null && !line.isEmpty()) {
                    long modified = ack.lastModified();
                    boolean freshMtime = modified > modifiedAfter;
                    boolean replaced = previousLine == null || !line.equals(previousLine);
                    // FUSE often has 1s mtime resolution: accept content change too.
                    if (freshMtime || replaced) {
                        return line;
                    }
                }
            }
            sleepQuietly(POLL_MS);
        }
        return null;
    }

    private static File ackDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return dir;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception failure) {
            Log.w(TAG, "read ack failed", failure);
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
