package com.vaonis.vesperahelper;

import android.content.Context;

/** Maps stable status codes from services to localized user-facing text. */
public final class StatusTexts {
    private StatusTexts() {}

    public static String connection(Context context, String code) {
        if (code == null || code.isEmpty()) {
            return context.getString(R.string.conn_disconnected);
        }
        if (VesperaConnectionService.STATUS_CONNECTED.equals(code)) {
            return context.getString(R.string.conn_connected);
        }
        if (VesperaConnectionService.STATUS_LOST.equals(code)) {
            return context.getString(R.string.conn_lost);
        }
        if (VesperaConnectionService.STATUS_UNAVAILABLE.equals(code)) {
            return context.getString(R.string.conn_unavailable);
        }
        if (VesperaConnectionService.STATUS_NEED_CONFIG.equals(code)) {
            return context.getString(R.string.conn_need_config);
        }
        if (VesperaConnectionService.STATUS_DISCONNECTED.equals(code)) {
            return context.getString(R.string.conn_disconnected);
        }
        if (code.startsWith(VesperaConnectionService.STATUS_REQUESTING + "|")) {
            String[] parts = code.split("\\|", 3);
            String model = parts.length > 1 ? parts[1] : "Vespera";
            String ssid = parts.length > 2 ? parts[2] : "";
            return context.getString(R.string.conn_requesting, model, ssid);
        }
        if (code.startsWith(VesperaConnectionService.STATUS_ERROR + "|")) {
            String name = code.substring((VesperaConnectionService.STATUS_ERROR + "|").length());
            return context.getString(R.string.conn_error, name);
        }
        return code;
    }

    /** Maps Singularity / instrument watchdog status codes to short UI labels. */
    public static String singularity(Context context, String code) {
        if (code == null || code.isEmpty() || InstrumentWatchdog.STATUS_IDLE.equals(code)) {
            return context.getString(R.string.singularity_state_idle);
        }
        if (InstrumentWatchdog.STATUS_CHECKING.equals(code)) {
            return context.getString(R.string.singularity_state_checking);
        }
        if (InstrumentWatchdog.STATUS_RECOVERING.equals(code)) {
            return context.getString(R.string.singularity_state_recovering);
        }
        if (InstrumentWatchdog.STATUS_STARTING.equals(code)) {
            return context.getString(R.string.singularity_state_starting);
        }
        if (SingularityDetector.Status.CONNECTED.name().equals(code)) {
            return context.getString(R.string.singularity_state_connected);
        }
        if (SingularityDetector.Status.NOT_RUNNING.name().equals(code)) {
            return context.getString(R.string.singularity_state_not_running);
        }
        if (SingularityDetector.Status.API_DOWN.name().equals(code)) {
            return context.getString(R.string.singularity_state_api_down);
        }
        if (SingularityDetector.Status.NO_WIFI.name().equals(code)) {
            return context.getString(R.string.singularity_state_no_wifi);
        }
        if (SingularityDetector.Status.DAEMON_MISSING.name().equals(code)) {
            return context.getString(R.string.singularity_state_no_daemon);
        }
        if (SingularityDetector.Status.DISCONNECTED.name().equals(code)
                || SingularityDetector.Status.UNKNOWN.name().equals(code)) {
            return context.getString(R.string.singularity_state_disconnected);
        }
        return code;
    }
}
