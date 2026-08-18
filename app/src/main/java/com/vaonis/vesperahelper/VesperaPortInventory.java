package com.vaonis.vesperahelper;

import android.content.Context;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Formats the latest {@link VesperaPortScan} for UI (Wi‑Fi + Telescopio + Foto). */
final class VesperaPortInventory {
    private VesperaPortInventory() {}

    static String formatFull(Context context, VesperaPortScan scan) {
        if (scan == null) {
            return context.getString(R.string.port_inventory_idle);
        }
        String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(scan.scannedAtMs));
        StringBuilder lines = new StringBuilder();
        lines.append(context.getString(R.string.port_inventory_header, scan.host, when));
        lines.append('\n');
        for (VesperaPortProbe probe : scan.probes) {
            lines.append(formatLine(context, probe)).append('\n');
        }
        lines.append(context.getString(R.string.port_inventory_summary,
                scan.openCount(), scan.probes.size()));
        if (scan.ftpPort > 0) {
            lines.append('\n').append(context.getString(R.string.port_inventory_ftp_active,
                    scan.ftpPort, userFolderUrl(scan)));
        }
        if (scan.apiRestPort > 0) {
            lines.append('\n').append(context.getString(R.string.port_inventory_api_active,
                    scan.apiRestPort));
        }
        return lines.toString().trim();
    }

    static String formatLine(Context context, VesperaPortProbe probe) {
        String state = probe.open
                ? context.getString(R.string.port_inventory_open)
                : context.getString(R.string.port_inventory_closed);
        String kind = probe.label;
        String extra = "";
        if (probe.open && !probe.detail.isEmpty()) {
            extra = " — " + probe.detail;
        }
        return context.getString(R.string.port_inventory_row, state, probe.port, kind, extra);
    }

    static String userFolderUrl(VesperaPortScan scan) {
        if (scan == null || scan.ftpPort <= 0) {
            return "ftp://10.0.0.1/USER";
        }
        return "ftp://" + scan.host + ":" + scan.ftpPort + "/USER";
    }

    static String userFolderUrl() {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.ftpPort > 0) {
            return userFolderUrl(scan);
        }
        int cached = FtpProbe.lastVesperaPort();
        if (cached > 0) {
            return "ftp://10.0.0.1:" + cached + "/USER";
        }
        return "ftp://10.0.0.1/USER";
    }
}
