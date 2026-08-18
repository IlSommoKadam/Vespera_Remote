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
        remember(targetOf(operation), operation);
    }

    /** Same target shown in the Telescopio status rows. */
    static void remember(JSONObject target, JSONObject operation) {
        JSONObject body = buildBody(target, operation);
        if (body == null || !hasCoordinates(body)) return;
        bodyJson = body.toString();
        label = firstNonEmpty(
                text(body, "objectName"),
                text(body, "name"),
                text(body, "catalogName"),
                formatCoordinates(body));
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

    static String formatCoordinates(JSONObject target) {
        double[] pair = raDecOf(target);
        if (pair == null) return "";
        return String.format(java.util.Locale.US, "RA %.3f  ·  Dec %.3f", pair[0], pair[1]);
    }

    private static JSONObject buildBody(JSONObject target, JSONObject operation) {
        JSONObject src = target != null ? target : operation;
        if (src == null) return null;
        JSONObject body = new JSONObject();
        copyNames(body, src);
        if (target != null && operation != null && target != operation) {
            copyNames(body, operation);
        }
        double[] pair = raDecOf(src);
        if (pair == null && operation != null && operation != src) {
            pair = raDecOf(operation);
        }
        if (pair == null) return null;
        try {
            body.put("ra", pair[0]);
            body.put("de", pair[1]);
            body.put("dec", pair[1]);
            body.put("resume", true);
        } catch (Exception ignored) {
            return null;
        }
        putIfPresent(body, src, "type");
        putIfPresent(body, src, "rot");
        putIfPresent(body, src, "objectType");
        if (operation != null) {
            putIfPresent(body, operation, "id");
            putIfPresent(body, operation, "uuid");
            putIfPresent(body, operation, "observationId");
            putIfPresent(body, operation, "taskId");
            putIfPresent(body, operation, "observationType");
            JSONObject store = operation.optJSONObject("store");
            if (store != null) putIfPresent(body, store, "storeId");
            JSONObject mosaic = operation.optJSONObject("mosaic");
            if (mosaic != null) {
                try {
                    body.put("mosaic", mosaic);
                } catch (Exception ignored) {
                }
            }
            JSONObject capture = operation.optJSONObject("capture");
            JSONObject cam = capture == null ? null : capture.optJSONObject("cameraParams");
            if (cam != null) {
                putIfPresent(body, cam, "gain");
                putIfPresent(body, cam, "exposureMicroSec");
            }
        }
        return body;
    }

    private static void copyNames(JSONObject dest, JSONObject src) {
        if (src == null) return;
        putIfPresent(dest, src, "objectName");
        putIfPresent(dest, src, "name");
        putIfPresent(dest, src, "catalogName");
        putIfPresent(dest, src, "objectId");
    }

    private static JSONObject findOperation(JSONObject payload) {
        if (payload == null) return null;
        String[] keys = {
                "currentOperation", "operation", "lastOperation",
                "observation", "lastObservation"
        };
        for (String key : keys) {
            JSONObject operation = payload.optJSONObject(key);
            if (targetOf(operation) != null) return operation;
        }
        JSONObject nested = payload.optJSONObject("result");
        if (nested == null) nested = payload.optJSONObject("data");
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
        JSONObject previousMap = payload.optJSONObject("previousOperations");
        if (previousMap != null) {
            JSONObject named = previousMap.optJSONObject("observation");
            if (targetOf(named) != null) return named;
            JSONArray names = previousMap.names();
            if (names != null) {
                for (int i = names.length() - 1; i >= 0; i--) {
                    JSONObject item = previousMap.optJSONObject(names.optString(i));
                    if (targetOf(item) != null) return item;
                }
            }
        }
        return payload.optJSONObject("operation");
    }

    private static JSONObject targetOf(JSONObject operation) {
        if (operation == null) return null;
        JSONObject target = operation.optJSONObject("target");
        if (target == null) target = operation.optJSONObject("object");
        if (raDecOf(target) != null) return target;
        if (raDecOf(operation) != null) return operation;
        return null;
    }

    private static boolean hasCoordinates(JSONObject obj) {
        return raDecOf(obj) != null;
    }

    private static double[] raDecOf(JSONObject obj) {
        if (obj == null) return null;
        Double ra = number(obj, "ra", "RA", "rightAscension", "raHours");
        Double dec = number(obj, "de", "dec", "DEC", "Dec", "declination", "decDeg");
        if (ra != null && dec != null) return new double[] { ra, dec };
        String[] nestedKeys = { "coordinates", "equatorial", "eq", "position", "pointing" };
        for (String key : nestedKeys) {
            double[] nested = raDecOf(obj.optJSONObject(key));
            if (nested != null) return nested;
        }
        return null;
    }

    private static Double number(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            Double value = asDouble(obj.opt(key));
            if (value != null) return value;
        }
        JSONArray names = obj.names();
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            for (String key : keys) {
                if (key.equalsIgnoreCase(name)) {
                    Double value = asDouble(obj.opt(name));
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            String text = ((String) value).trim().replace(',', '.');
            if (text.isEmpty()) return null;
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Double nested = asDouble(obj.opt("value"));
            if (nested != null) return nested;
            nested = asDouble(obj.opt("hours"));
            if (nested != null) return nested;
            return asDouble(obj.opt("deg"));
        }
        return null;
    }

    private static void putIfPresent(JSONObject dest, JSONObject src, String key) {
        if (src == null || !src.has(key) || src.isNull(key)) return;
        try {
            dest.put(key, src.get(key));
        } catch (Exception ignored) {
        }
    }

    private static String text(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        return String.valueOf(obj.opt(key)).trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
