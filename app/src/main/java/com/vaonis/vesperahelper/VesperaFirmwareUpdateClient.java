package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

final class VesperaFirmwareUpdateClient {
    private static final String TAG = "VesperaFwUpdate";

    private static final int PROBE_TIMEOUT_MS = 2_500;
    private static final int UPLOAD_TIMEOUT_MS = 240_000;

    interface Progress {
        void onProgress(long sent, long total);
    }

    static final class Result {
        final boolean success;
        final int httpCode;
        final String message;
        final String usedPath;

        Result(boolean success, int httpCode, String message, String usedPath) {
            this.success = success;
            this.httpCode = httpCode;
            this.message = message == null ? "" : message;
            this.usedPath = usedPath == null ? "" : usedPath;
        }
    }

    private VesperaFirmwareUpdateClient() {}

    static Result uploadSwu(Network network, String host, int port,
            VesperaStatusSnapshot snap, File swuFile, Progress progress) {
        if (snap == null || swuFile == null) {
            return new Result(false, -1, "missing-input", "");
        }
        if (!swuFile.isFile()) {
            return new Result(false, -1, "swu-not-a-file", "");
        }
        if (!snap.canSignCommands()) {
            return new Result(false, -1, "auth_missing", "");
        }

        String authorization;
        try {
            authorization = VesperaApiAuth.authorizationHeader(snap);
        } catch (Exception e) {
            return new Result(false, -1, "auth_sign_failed", "");
        }
        if (authorization.isEmpty()) {
            return new Result(false, -1, "auth_sign_failed", "");
        }

        ListPaths paths = new ListPaths();
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        if (port <= 0) port = 8082;

        String usedPath = null;
        for (String candidate : paths.candidates) {
            try {
                // Small probe without file: if path exists it should not be 404.
                VesperaHttp.Response r = VesperaHttp.post(network, host, port, candidate,
                        "{}", authorization, PROBE_TIMEOUT_MS);
                if (r.code != 404 && r.code != 0) {
                    usedPath = candidate;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (usedPath == null) {
            return new Result(false, -1, "upload_endpoint_not_confirmed", "");
        }

        long fileSize = swuFile.length();
        try (InputStream in = new FileInputStream(swuFile)) {
            VesperaHttp.Response r = VesperaHttp.postMultipartFile(
                    network,
                    host,
                    port,
                    usedPath,
                    swuFile.getName(),
                    "application/octet-stream",
                    fileSize,
                    in,
                    authorization,
                    (sent, total) -> {
                        if (progress != null) progress.onProgress(sent, total);
                    },
                    UPLOAD_TIMEOUT_MS);
            boolean ok = r.code >= 200 && r.code < 300;
            String msg = ok ? "upload_ok" : "HTTP " + r.code;
            if (!r.body.isEmpty()) {
                msg += " " + truncate(r.body, 300);
            }
            return new Result(ok, r.code, msg, usedPath);
        } catch (Exception failure) {
            Log.w(TAG, "upload failed: " + failure.getMessage());
            return new Result(false, -1, String.valueOf(failure.getMessage()), usedPath);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** Internal list container to keep Java version compatibility (no Arrays.asList on older min). */
    private static final class ListPaths {
        final String[] candidates = new String[] {
                "/v1/updates/uploadUpdateFile",
                "/v2/updates/uploadUpdateFile",
                "/updates/uploadUpdateFile"
        };
    }
}

