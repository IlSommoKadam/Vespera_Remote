package com.vaonis.vesperahelper;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** SNTP (UDP 123) over Ethernet/Internet. Does not need the Vespera. */
final class NtpClient {
    private static final String TAG = "VesperaNtp";
    private static final String[] HOSTS = {
            "time.google.com",
            "time.cloudflare.com",
            "pool.ntp.org"
    };
    private static final long NTP_UNIX_OFFSET = 2_208_988_800L;

    private NtpClient() {}

    static long unixTimeMs() throws Exception {
        Exception last = null;
        for (String host : HOSTS) {
            try {
                return query(host);
            } catch (Exception failure) {
                last = failure;
                Log.w(TAG, host + ": " + failure.getMessage());
            }
        }
        throw last != null ? last : new Exception("ntp failed");
    }

    private static long query(String host) throws Exception {
        byte[] request = new byte[48];
        request[0] = 0x1B;
        InetAddress address = InetAddress.getByName(host);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(4_000);
            socket.send(new DatagramPacket(request, request.length, address, 123));
            byte[] response = new byte[48];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            long seconds = u32(response, 40);
            long fraction = u32(response, 44);
            long unixSec = seconds - NTP_UNIX_OFFSET;
            long millis = (fraction * 1000L) >>> 32;
            if (unixSec < 1_700_000_000L || unixSec > 4_000_000_000L) {
                throw new Exception("ntp range " + unixSec);
            }
            return unixSec * 1000L + millis;
        }
    }

    private static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }
}
