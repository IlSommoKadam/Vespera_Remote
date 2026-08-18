package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Fetches Vespera REST status (StellinAPI) without authentication. */
final class VesperaStatusClient {
    private static final String TAG = "VesperaStatus";
    private static final int TIMEOUT_MS = 4_000;
    private static final String[] PATHS = {"/v2/app/status", "/v1/app/status"};

    private VesperaStatusClient() {}

    static VesperaStatusSnapshot fetch(String host, int preferredPort, Network network) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        for (int port : restPorts(preferredPort)) {
            for (String path : PATHS) {
                try {
                    String json = httpGet(network, host, port, path);
                    if (json == null || json.isEmpty()) continue;
                    VesperaStatusSnapshot snap = parse(host + ":" + port + path, json);
                    if (snap.hasInstrumentFields() || json.length() > 4) {
                        return snap;
                    }
                } catch (Exception failure) {
                    Log.d(TAG, "status " + host + ":" + port + path + ": " + failure.getMessage());
                }
            }
        }
        return null;
    }

    private static int[] restPorts(int preferredPort) {
        if (preferredPort == 8082) return new int[] {8082, 8083};
        if (preferredPort == 8083) return new int[] {8082, 8083};
        return new int[] {8082, 8083};
    }

    private static String httpGet(Network network, String host, int port, String path)
            throws Exception {
        URL url = new URL("http://" + host + ":" + port + path);
        HttpURLConnection conn = network != null
                ? (HttpURLConnection) network.openConnection(url)
                : (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
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
        } finally {
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        return body.toString();
    }

    static VesperaStatusSnapshot parse(String endpoint, String json) {
        JSONObject root = new JSONObject(json);
        JSONObject payload = unwrap(root);
        String telescopeId = text(payload, "telescopeId");
        String challenge = text(payload, "challenge");
        int bootCount = payload.optInt("bootCount", payload.optInt("boot_count", 0));
        String model = text(payload, "model");
        String state = text(payload, "state");
        boolean initialized = payload.optBoolean("initialized", false);
        JSONObject operation = payload.optJSONObject("operation");
        String operationType = operation != null ? text(operation, "type") : "";
        String targetName = "";
        int stacking = -1;
        long exposureUs = 0;
        int gain = -1;
        if (operation != null) {
            JSONObject target = operation.optJSONObject("target");
            if (target != null) {
                targetName = firstNonEmpty(
                        text(target, "objectName"),
                        text(target, "name"),
                        text(target, "catalogName"));
            }
            JSONObject capture = operation.optJSONObject("capture");
            if (capture != null) {
                stacking = capture.optInt("stackingCount", -1);
                exposureUs = capture.optLong("exposureMicroSec", 0);
                gain = capture.optInt("gain", -1);
            }
        }
        int batteryPercent = -1;
        String batteryStatus = "";
        JSONObject battery = payload.optJSONObject("internalBattery");
        if (battery == null) battery = payload.optJSONObject("battery");
        if (battery != null) {
            batteryPercent = battery.optInt("chargeLevel", battery.optInt("level", -1));
            batteryStatus = firstNonEmpty(
                    text(battery, "chargeStatus"), text(battery, "status"));
        }
        return new VesperaStatusSnapshot(endpoint, telescopeId, model, state, initialized,
                operationType, targetName, Math.max(0, stacking), exposureUs,
                Math.max(0, gain), batteryPercent, batteryStatus, challenge, bootCount, json);
    }

    /** REST status merged with Socket.IO challenge when needed for API commands. */
    static VesperaStatusSnapshot fetchForAuth(String host, int preferredPort, Network network) {
        VesperaStatusSnapshot snap = fetch(host, preferredPort, network);
        if (snap == null) return null;
        if (snap.hasAuthFields()) return snap;
        VesperaStatusSnapshot socket = VesperaSocketChallenge.fetch(host, 8083, network);
        return socket != null ? snap.withAuthFrom(socket) : snap;
    }

    private static JSONObject unwrap(JSONObject root) {
        if (root.has("data") && root.opt("data") instanceof JSONObject) {
            return root.getJSONObject("data");
        }
        if (root.has("status") && root.opt("status") instanceof JSONObject) {
            return root.getJSONObject("status");
        }
        return root;
    }

    private static String text(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        Object value = obj.opt(key);
        if (value instanceof String) return ((String) value).trim();
        return String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
