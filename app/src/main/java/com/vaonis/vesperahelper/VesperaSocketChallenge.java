package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Fetches a fresh {@code challenge} via Engine.IO / Socket.IO polling on port 8083.
 * Used when REST status does not include the challenge field.
 */
final class VesperaSocketChallenge {
    private static final String TAG = "VesperaSocket";
    private static final int TIMEOUT_MS = 5_000;
    private static final int POLL_ROUNDS = 12;
    private static final long POLL_DELAY_MS = 350;

    private VesperaSocketChallenge() {}

    static VesperaStatusSnapshot fetch(String host, int socketPort, Network network) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        int port = socketPort > 0 ? socketPort : 8083;
        try {
            String base = "http://" + host + ":" + port + "/socket.io/?EIO=4&transport=polling";
            String handshake = httpGet(network, base);
            if (handshake == null || handshake.length() < 2 || handshake.charAt(0) != '0') {
                return null;
            }
            JSONObject open = new JSONObject(handshake.substring(1));
            String sid = open.optString("sid", "");
            if (sid.isEmpty()) return null;

            String pollUrl = base + "&sid=" + URLEncoder.encode(sid, "UTF-8");
            httpPost(network, pollUrl, "40");

            for (int round = 0; round < POLL_ROUNDS; round++) {
                String payload = httpGet(network, pollUrl);
                if (payload != null) {
                    VesperaStatusSnapshot snap = parsePayload(host + ":" + port, payload);
                    if (snap != null) return snap;
                }
                Thread.sleep(POLL_DELAY_MS);
            }
        } catch (Exception failure) {
            Log.d(TAG, "challenge poll: " + failure.getMessage());
        }
        return null;
    }

    private static VesperaStatusSnapshot parsePayload(String endpoint, String payload) {
        int idx = 0;
        while (idx < payload.length()) {
            int typeEnd = idx;
            while (typeEnd < payload.length() && Character.isDigit(payload.charAt(typeEnd))) {
                typeEnd++;
            }
            if (typeEnd == idx) break;
            int packetType = Integer.parseInt(payload.substring(idx, typeEnd));
            idx = typeEnd;
            if (packetType == 2) {
                // Engine.IO ping — ignore; server may auto-pong.
                continue;
            }
            if (packetType == 42) {
                String json = payload.substring(idx);
                int arrayStart = json.indexOf('[');
                if (arrayStart < 0) break;
                String eventBlock = json.substring(arrayStart);
                int objStart = eventBlock.indexOf('{');
                int objEnd = eventBlock.lastIndexOf('}');
                if (objStart < 0 || objEnd <= objStart) break;
                String statusJson = eventBlock.substring(objStart, objEnd + 1);
                JSONObject status = new JSONObject(statusJson);
                if (!status.has("challenge") || status.optString("challenge").isEmpty()) {
                    idx = payload.length();
                    continue;
                }
                return VesperaStatusClient.parse(endpoint + "/socket.io", statusJson);
            }
            break;
        }
        return null;
    }

    private static String httpGet(Network network, String url) throws Exception {
        HttpURLConnection conn = open(network, url);
        conn.setRequestMethod("GET");
        return read(conn);
    }

    private static void httpPost(Network network, String url, String body) throws Exception {
        HttpURLConnection conn = open(network, url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        read(conn);
    }

    private static HttpURLConnection open(Network network, String url) throws Exception {
        HttpURLConnection conn = network != null
                ? (HttpURLConnection) network.openConnection(new URL(url))
                : (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    private static String read(HttpURLConnection conn) throws Exception {
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }
}
