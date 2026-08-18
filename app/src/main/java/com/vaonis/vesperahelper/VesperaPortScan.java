package com.vaonis.vesperahelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Result of a full Vespera port inventory on one host. */
final class VesperaPortScan {
    final String host;
    final long scannedAtMs;
    final List<VesperaPortProbe> probes;
    final int ftpPort;
    final int apiRestPort;
    final int apiSocketPort;

    VesperaPortScan(String host, long scannedAtMs, List<VesperaPortProbe> probes) {
        this.host = host == null ? "10.0.0.1" : host;
        this.scannedAtMs = scannedAtMs;
        this.probes = probes == null ? Collections.emptyList() : Collections.unmodifiableList(
                new ArrayList<>(probes));
        int ftp = -1;
        int rest = -1;
        int socket = -1;
        for (VesperaPortProbe probe : this.probes) {
            if (!probe.open) continue;
            if (probe.kind == VesperaPortProbe.Kind.FTP && ftp < 0) ftp = probe.port;
            if (probe.kind == VesperaPortProbe.Kind.API_REST && rest < 0) rest = probe.port;
            if (probe.kind == VesperaPortProbe.Kind.API_SOCKET && socket < 0) {
                socket = probe.port;
            }
        }
        this.ftpPort = ftp;
        this.apiRestPort = rest;
        this.apiSocketPort = socket;
    }

    int openCount() {
        int count = 0;
        for (VesperaPortProbe probe : probes) {
            if (probe.open) count++;
        }
        return count;
    }
}
