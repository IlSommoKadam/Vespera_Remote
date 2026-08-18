package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Probes all known Vespera FTP and API ports; keeps the latest scan in memory. */
final class VesperaPortScanner {
    private static final String TAG = "VesperaPortScan";
    private static final int TIMEOUT_MS = 2_500;
    private static volatile VesperaPortScan lastScan;

    private VesperaPortScanner() {}

    static VesperaPortScan lastScan() {
        return lastScan;
    }

    static void clear() {
        lastScan = null;
        FtpProbe.clearCache();
    }

    static VesperaPortScan scan(Network network, String host) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        List<VesperaPortProbe> probes = new ArrayList<>();
        for (int port : FtpProbe.VESPERA_FTP_PORTS) {
            probes.add(probeFtp(network, host, port));
        }
        probes.add(probeApiRest(network, host, 8082));
        probes.add(probeApiSocket(network, host, 8083));
        VesperaPortScan scan = new VesperaPortScan(host, System.currentTimeMillis(), probes);
        lastScan = scan;
        if (scan.ftpPort > 0) {
            FtpProbe.rememberVesperaPort(scan.ftpPort);
        }
        Log.i(TAG, "scan " + host + " open=" + scan.openCount() + " ftp=" + scan.ftpPort
                + " api=" + scan.apiRestPort);
        return scan;
    }

    private static VesperaPortProbe probeFtp(Network network, String host, int port) {
        String banner = readFtpBanner(network, host, port);
        boolean open = banner != null;
        return new VesperaPortProbe(port, VesperaPortProbe.Kind.FTP, "FTP", open,
                open ? banner : "");
    }

    private static VesperaPortProbe probeApiRest(Network network, String host, int port) {
        String detail = probeHttpHead(network, host, port, "/v2/app/status");
        if (detail == null) {
            detail = probeHttpHead(network, host, port, "/v1/app/status");
        }
        boolean open = detail != null;
        if (!open) {
            detail = tcpDetail(network, host, port);
            open = detail != null;
        }
        return new VesperaPortProbe(port, VesperaPortProbe.Kind.API_REST, "API REST", open,
                open ? detail : "");
    }

    private static VesperaPortProbe probeApiSocket(Network network, String host, int port) {
        String detail = tcpDetail(network, host, port);
        boolean open = detail != null;
        return new VesperaPortProbe(port, VesperaPortProbe.Kind.API_SOCKET, "API Socket.IO",
                open, open ? detail : "");
    }

    private static String readFtpBanner(Network network, String host, int port) {
        if (!FtpProbe.isFtpControl(network, host, port)) return null;
        Socket socket = null;
        try {
            socket = VesperaSockets.create(network);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return "FTP";
            if (line.length() > 48) line = line.substring(0, 48) + "…";
            return line;
        } catch (Exception ignored) {
            return "FTP";
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String probeHttpHead(Network network, String host, int port, String path) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + port + path);
            conn = network != null
                    ? (HttpURLConnection) network.openConnection(url)
                    : (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 500) {
                return "HTTP " + code;
            }
        } catch (Exception ignored) {
            // fall through
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String tcpDetail(Network network, String host, int port) {
        Socket socket = null;
        try {
            socket = VesperaSockets.create(network);
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return "TCP";
        } catch (Exception ignored) {
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
