package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;

/**
 * Opens TCP sockets toward the Vespera. Prefers the instrument {@link Network}
 * so traffic stays on Wi‑Fi; if Android denies the bind ({@code EPERM}), falls
 * back to a normal socket so the daemon {@code 10.0.0.0/24 → wlan0} rule can
 * still deliver the connection.
 */
final class VesperaSockets {
    private static final String TAG = "VesperaSock";

    private VesperaSockets() {}

    static Socket create(Network network) throws IOException {
        if (network == null) return new Socket();
        try {
            return network.getSocketFactory().createSocket();
        } catch (IOException factoryFail) {
            Log.w(TAG, "factory " + network + ": " + factoryFail.getMessage());
        }
        try {
            Socket socket = new Socket();
            network.bindSocket(socket);
            return socket;
        } catch (IOException bindFail) {
            Log.w(TAG, "bindSocket " + network + " failed, using default route: "
                    + bindFail.getMessage());
            return new Socket();
        }
    }
}
