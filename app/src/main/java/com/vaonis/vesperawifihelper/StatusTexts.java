package com.vaonis.vesperawifihelper;

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
}
