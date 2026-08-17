package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Discovers the Vespera FTP control port and a free listen port on the Pi. */
final class FtpProbe {
    private static final String TAG = "VesperaFtpProbe";
    static final int HD_PREFERRED = 2121;
    static final int TELESCOPE_PREFERRED = 2122;
    static final int[] VESPERA_CONTROL = {21, 2121, 2221, 8021};
    private static final int[] LISTEN_FALLBACK = {2121, 2122, 2123, 2124, 2125, 2126, 2127};
    private static volatile int cachedVesperaPort = -1;

    private FtpProbe() {}

    static int lastVesperaPort() {
        return cachedVesperaPort;
    }

    static int findVesperaControl(Network network, String host) {
        if (host == null || host.isEmpty()) return -1;
        if (cachedVesperaPort > 0 && isFtpControl(network, host, cachedVesperaPort)) {
            return cachedVesperaPort;
        }
        for (int port : VESPERA_CONTROL) {
            if (isFtpControl(network, host, port)) {
                cachedVesperaPort = port;
                Log.i(TAG, "vespera FTP control on " + host + ":" + port);
                return port;
            }
        }
        cachedVesperaPort = -1;
        return -1;
    }

    static boolean isFtpControl(Network network, String host, int port) {
        Socket socket = null;
        try {
            socket = VesperaSockets.create(network);
            socket.connect(new InetSocketAddress(host, port), 2_500);
            socket.setSoTimeout(2_500);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line != null && (line.startsWith("220") || line.startsWith("120"));
        } catch (Exception ignored) {
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    static int bindPreferred(ServerSocket server, int preferred, int avoid) throws IOException {
        IOException last = null;
        int[] order = new int[1 + LISTEN_FALLBACK.length];
        order[0] = preferred;
        System.arraycopy(LISTEN_FALLBACK, 0, order, 1, LISTEN_FALLBACK.length);
        for (int port : order) {
            if (port <= 0 || port == avoid) continue;
            try {
                server.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IOException("no free FTP listen port");
    }
}
