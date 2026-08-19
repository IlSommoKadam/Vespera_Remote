package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

/**
 * Sends Vespera REST commands with Ed25519 challenge-response auth.
 */
final class VesperaCommandClient {
    private static final String TAG = "VesperaCmd";
    private static final int TIMEOUT_MS = 8_000;

    enum Command {
        PARK("/v1/general/park"),
        STOP("/v1/general/stopObservation"),
        RESUME("/v1/general/startObservation"),
        INIT("/v1/general/startAutoInit"),
        SHUTDOWN("/v1/board/requestShutdown");

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
        return send(host, apiPort, network, command, null);
    }

    static Result send(String host, int apiPort, Network network, Command command,
            VesperaLocationClient.Site initSite) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        int port = apiPort > 0 ? apiPort : 8082;
        if (port == 8083) port = 8082;
        VesperaStatusClient.Result status = VesperaStatusClient.fetchResult(host, port, network);
        VesperaStatusSnapshot snap = status.snapshot;
        if (snap == null) {
            String detail = status.error.isEmpty() ? "status_unavailable" : status.error;
            return new Result(false, -1, "status_unavailable: " + detail);
        }
        if (!snap.canSignCommands()) {
            return new Result(false, -1, snap.authMissingCode());
        }
        String body = "{}";
        boolean haveTarget = false;
        if (command == Command.RESUME) {
            body = VesperaLastTarget.startObservationBody(snap);
            haveTarget = VesperaLastTarget.hasTarget() && !body.isEmpty();
            if (!haveTarget) body = "{\"resume\":true}";
        } else if (command == Command.INIT) {
            body = autoInitBody(initSite);
            if (body.isEmpty()) {
                return new Result(false, -1, "no_site");
            }
        }
        String authorization = VesperaApiAuth.authorizationHeader(snap);
        if (authorization.isEmpty()) {
            return new Result(false, -1, "auth_sign_failed");
        }
        String[] paths;
        if (command == Command.SHUTDOWN) {
            paths = new String[] {
                    command.path,
                    "/v1/general/shutdown",
                    "/v1/general/powerOff",
                    "/v1/device/shutdown"
            };
        } else if (command == Command.RESUME) {
            paths = new String[] { command.path, "/v1/general/resumeObservation" };
        } else {
            paths = new String[] { command.path };
        }
        Result last = new Result(false, -1, "HTTP");
        try {
            for (String path : paths) {
                boolean shutdown = command == Command.SHUTDOWN;
                VesperaHttp.Response response = VesperaHttp.post(
                        network, host, port, path, body, authorization, TIMEOUT_MS, shutdown);
                if (shutdown && response.code == 0) {
                    return new Result(true, 0, "shutdown_started");
                }
                boolean ok = response.code >= 200 && response.code < 300
                        && !isFirmwareFailure(response.body);
                if (!ok && response.code == 401) {
                    return new Result(false, response.code, "auth_required");
                }
                String msg = response.body.isEmpty()
                        ? ("HTTP " + response.code) : truncate(response.body, 400);
                last = new Result(ok, response.code, msg);
                if (ok) {
                    if (shutdown) return new Result(true, response.code, "shutdown_started");
                    return last;
                }
                boolean tryNext = response.code == 404 || response.code == 405
                        || (command == Command.RESUME && isIncorrectParams(response.body));
                if (!tryNext) return last;
            }
            if (command == Command.RESUME && !haveTarget) {
                return new Result(false, last.httpCode,
                        last.message.isEmpty() ? "no_target" : last.message);
            }
            return last;
        } catch (Exception failure) {
            Log.w(TAG, command.path + ": " + failure.getMessage());
            if (command == Command.SHUTDOWN && VesperaHttp.isHangup(failure)) {
                return new Result(true, 0, "shutdown_started");
            }
            return new Result(false, -1, failure.getMessage());
        }
    }

    /**
     * Firmware {@code startAutoInit} requires {@code time} (Date.now ms),
     * {@code latitude} and {@code longitude}. Empty {@code {}} is rejected with
     * CHECKPARAMS.INCORRECT_PARAMS.
     */
    private static String autoInitBody(VesperaLocationClient.Site site) {
        if (site == null) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("time", System.currentTimeMillis());
            body.put("latitude", site.lat);
            body.put("longitude", site.lon);
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isIncorrectParams(String responseBody) {
        if (responseBody == null) return false;
        String text = responseBody.toUpperCase(java.util.Locale.US);
        return text.contains("INCORRECT_PARAMS") || text.contains("CHECKPARAMS");
    }

    private static boolean isFirmwareFailure(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return false;
        String trim = responseBody.trim();
        if (trim.charAt(0) != '{') return false;
        try {
            JSONObject json = new JSONObject(trim);
            if (json.has("success") && !json.optBoolean("success", true)) return true;
            return false;
        } catch (Exception ignored) {
            return isIncorrectParams(responseBody);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
