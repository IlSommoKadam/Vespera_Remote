package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends Vespera REST commands directly (no Singularity app).
 * Control endpoints may return 401 without API auth (phase 2).
 */
final class VesperaCommandClient {
    private static final String TAG = "VesperaCmd";
    private static final int TIMEOUT_MS = 8_000;

    enum Command {
        PARK("/v1/general/park"),
        STOP("/v1/general/stopObservation"),
        INIT("/v1/general/startAutoInit");

        final String path;

        Command(String path) {
            this.path = path;
        }
    }

    static final class Result {
        final boolean success;
        final int httpCode;
        final String message;

        Result(boolean success, int httpCode, String message) {
            this.success = success;
            this.httpCode = httpCode;
            this.message = message == null ? "" : message;
        }
    }

    private VesperaCommandClient() {}

    static Result send(String host, int apiPort, Network network, Command command) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        int port = apiPort > 0 ? apiPort : 8082;
        if (port == 8083) port = 8082;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + port + command.path);
            conn = network != null
                    ? (HttpURLConnection) network.openConnection(url)
                    : (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            String response = readBody(conn, code);
            boolean ok = code >= 200 && code < 300;
            if (!ok && code == 401) {
                return new Result(false, code, "auth_required");
            }
            String msg = response.isEmpty() ? ("HTTP " + code) : truncate(response, 200);
            return new Result(ok, code, msg);
        } catch (Exception failure) {
            Log.w(TAG, command.path + ": " + failure.getMessage());
            return new Result(false, -1, failure.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
