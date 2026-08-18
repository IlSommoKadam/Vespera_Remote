package com.vaonis.vesperahelper;

/** One probed Vespera port (open or closed). */
final class VesperaPortProbe {
    enum Kind {
        FTP,
        API_REST,
        API_SOCKET
    }

    final int port;
    final Kind kind;
    final String label;
    final boolean open;
  /** FTP banner, HTTP code, or empty when closed. */
    final String detail;

    VesperaPortProbe(int port, Kind kind, String label, boolean open, String detail) {
        this.port = port;
        this.kind = kind;
        this.label = label == null ? "" : label;
        this.open = open;
        this.detail = detail == null ? "" : detail;
    }
}
