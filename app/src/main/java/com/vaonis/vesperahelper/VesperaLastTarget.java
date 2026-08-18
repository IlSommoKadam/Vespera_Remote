package com.vaonis.vesperahelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Last observation target from live/REST status, used to POST startObservation.
 */
final class VesperaLastTarget {
    private static volatile String bodyJson = "";
    private static volatile String label = "";

    private VesperaLastTarget() {}

    static void rememberFromStatus(JSONObject payload) {
        JSONObject operation = findOperation(payload);
        JSONObject target = targetOf(operation);
        if (target == null || !hasCoordinates(target)) return;
        JSONObject body = copyTarget(target);
        putIfPresent(body, operation, "id");
        putIfPresent(body, operation, "uuid");
        putIfPresent(body, operation, "observationId");
        putIfPresent(body, operation, "taskId");
        try {
            body.put("resume", true);
        } catch (Exception ignored) {
        }
        bodyJson = body.toString();
        label = firstNonEmpty(
                text(body, "objectName"),
                text(body, "name"),
                text(body, "catalogName"),
                coordinatesLabel(body));
    }

    static boolean hasTarget() {
        return bodyJson != null && !bodyJson.isEmpty();
    }

    static String startObservationBody() {
        return hasTarget() ? bodyJson : "";
    }

    static String label() {
        return label == null ? "" : label;
    }

    private static JSONObject findOperation(JSONObject payload) {
        if (payload == null) return null;
        JSONObject operation = payload.optJSONObject("operation");
        if (targetOf(operation) != null) return operation;
        JSONObject nested = payload.optJSONObject("result");
        if (nested != null && nested != payload) {
            JSONObject fromNested = findOperation(nested);
            if (fromNested != null) return fromNested;
        }
        JSONArray previous = payload.optJSONArray("previousOperations");
        if (previous != null) {
            for (int i = previous.length() - 1; i >= 0; i--) {
                JSONObject item = previous.optJSONObject(i);
                if (targetOf(item) != null) return item;
            }
        }
        return operation;
    }

    private static JSONObject targetOf(JSONObject operation) {
        if (operation == null) return null;
        JSONObject target = operation.optJSONObject("target");
        if (target != null && hasCoordinates(target)) return target;
        return hasCoordinates(operation) ? operation : null;
    }

    private static boolean hasCoordinates(JSONObject obj) {
        if (obj == null) return false;
        return obj.has("ra") && (obj.has("de") || obj.has("dec"));
    }

    private static JSONObject copyTarget(JSONObject target) {
        JSONObject body = new JSONObject();
        putIfPresent(body, target, "objectName");
        putIfPresent(body, target, "name");
        putIfPresent(body, target, "catalogName");
        putIfPresent(body, target, "objectId");
        putNumber(body, target, "ra");
        if (target.has("de")) putNumber(body, target, "de");
        if (target.has("dec")) putNumber(body, target, "dec");
        if (body.has("de") && !body.has("dec")) {
            try {
                body.put("dec", body.get("de"));
            } catch (Exception ignored) {
            }
        }
        if (body.has("dec") && !body.has("de")) {
            try {
                body.put("de", body.get("dec"));
            } catch (Exception ignored) {
            }
        }
        return body;
    }

    private static void putIfPresent(JSONObject dest, JSONObject src, String key) {
        if (!src.has(key) || src.isNull(key)) return;
        try {
            dest.put(key, src.get(key));
        } catch (Exception ignored) {
        }
    }

    private static void putNumber(JSONObject dest, JSONObject src, String key) {
        if (!src.has(key) || src.isNull(key)) return;
        try {
            dest.put(key, src.getDouble(key));
        } catch (Exception ignored) {
        }
    }

    private static String text(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        return String.valueOf(obj.opt(key)).trim();
    }

    private static String coordinatesLabel(JSONObject obj) {
        try {
            return String.format(java.util.Locale.US, "RA %.3f / Dec %.3f",
                    obj.getDouble("ra"),
                    obj.has("de") ? obj.getDouble("de") : obj.getDouble("dec"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
