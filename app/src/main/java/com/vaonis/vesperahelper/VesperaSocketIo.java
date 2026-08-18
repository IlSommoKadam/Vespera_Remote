package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine.IO / Socket.IO long-poll client for Vespera port 8083.
 * Handshake query must include {@code id} and {@code name}; events arrive as
 * {@code 42["STATUS_UPDATED", {...}]}.
 */
final class VesperaSocketIo {
    private static final String TAG = "VesperaSIO";
    static final int PORT = 8083;
    private static final String CLIENT_ID = "vesperahelper";
    private static final String CLIENT_NAME = "VesperaHelper";
    private static final int POLL_TIMEOUT_MS = 35_000;
    private static final int SHORT_TIMEOUT_MS = 8_000;

    interface Listener {
        void onStatus(VesperaStatusSnapshot snapshot);

        void onInfo(String message);

        void onError(String message);
    }

    private final Listener listener;
    private volatile boolean stop;
    private volatile Socket current;

    VesperaSocketIo(Listener listener) {
        this.listener = listener;
    }

    void stop() {
        stop = true;
        closeCurrent();
    }

    void run(String host, Network network) {
        stop = false;
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        String sid = null;
        String cookie = null;
        while (!stop) {
            try {
                if (sid == null) {
                    listener.onInfo("handshake");
                    VesperaHttp.Response open = exchange(network, host, "GET",
                            path(null), null, null, SHORT_TIMEOUT_MS);
                    if (stop) break;
                    if (open.code < 200 || open.code >= 300) {
                        throw new IOException("handshake HTTP " + open.code);
                    }
                    sid = sidFromPackets(decodePackets(open.body));
                    if (sid == null || sid.isEmpty()) {
                        throw new IOException("handshake sid");
                    }
                    cookie = "io=" + sid;
                    handlePackets(host, decodePackets(open.body));
                }
                VesperaHttp.Response poll = exchange(network, host, "GET",
                        path(sid), null, cookie, POLL_TIMEOUT_MS);
                if (stop) break;
                if (poll.code == 400) {
                    Log.w(TAG, "session " + poll.body);
                    sid = null;
                    cookie = null;
                    continue;
                }
                if (poll.code < 200 || poll.code >= 300) {
                    throw new IOException("poll HTTP " + poll.code);
                }
                List<String> packets = decodePackets(poll.body);
                boolean needPong = handlePackets(host, packets);
                if (needPong && !stop) {
                    try {
                        exchange(network, host, "POST", path(sid), "3", cookie, SHORT_TIMEOUT_MS);
                    } catch (Exception pongFail) {
                        Log.w(TAG, "pong: " + pongFail.getMessage());
                        sid = null;
                        cookie = null;
                    }
                }
            } catch (Exception failure) {
                if (stop) break;
                String msg = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                if (!(failure instanceof SocketException)) {
                    Log.w(TAG, msg);
                    listener.onError(msg);
                }
                sid = null;
                cookie = null;
                sleepQuiet(1_500);
            }
        }
        closeCurrent();
    }

    private boolean handlePackets(String host, List<String> packets) {
        boolean ping = false;
        for (String packet : packets) {
            if (packet == null || packet.isEmpty()) continue;
            if ("2".equals(packet) || "2probe".equals(packet)) {
                ping = true;
                continue;
            }
            if (packet.startsWith("42")) {
                dispatchEvent(host, packet.substring(2));
                continue;
            }
            if (packet.startsWith("0{")) {
                continue;
            }
            if ("1".equals(packet) || packet.startsWith("1{")) {
                throw new RuntimeException("engine.io close");
            }
        }
        return ping;
    }

    private void dispatchEvent(String host, String jsonArray) {
        try {
            JSONArray event = new JSONArray(jsonArray);
            if (event.length() < 2) return;
            Object name = event.opt(0);
            Object payload = event.opt(1);
            if (payload instanceof String) {
                Log.w(TAG, String.valueOf(payload));
                return;
            }
            if (!(payload instanceof JSONObject)) return;
            String eventName = name instanceof String ? (String) name : "event";
            String endpoint = host + ":" + PORT + " " + eventName;
            VesperaStatusSnapshot snap = VesperaStatusClient.parse(
                    endpoint, payload.toString());
            listener.onStatus(snap);
        } catch (Exception parseFail) {
            Log.w(TAG, "event: " + parseFail.getMessage());
        }
    }

    private static String sidFromPackets(List<String> packets) {
        for (String packet : packets) {
            if (packet != null && packet.startsWith("0{")) {
                try {
                    JSONObject open = new JSONObject(packet.substring(1));
                    return open.optString("sid", "");
                } catch (Exception ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    static List<String> decodePackets(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        String text = body.trim();
        if (text.isEmpty()) return out;
        int i = 0;
        while (i < text.length()) {
            int colon = text.indexOf(':', i);
            if (colon < 0) {
                if (text.charAt(i) == '\u001e') {
                    i++;
                    continue;
                }
                out.add(text.substring(i));
                break;
            }
            int len;
            try {
                len = Integer.parseInt(text.substring(i, colon));
            } catch (NumberFormatException ignored) {
                int rs = text.indexOf('\u001e', i);
                if (rs < 0) {
                    out.add(text.substring(i));
                    break;
                }
                out.add(text.substring(i, rs));
                i = rs + 1;
                continue;
            }
            int start = colon + 1;
            int end = start + len;
            if (end > text.length()) {
                out.add(text.substring(start));
                break;
            }
            out.add(text.substring(start, end));
            i = end;
        }
        return out;
    }

    private static String path(String sid) {
        StringBuilder q = new StringBuilder("/socket.io/?EIO=4&transport=polling");
        q.append("&id=").append(enc(CLIENT_ID));
        q.append("&name=").append(enc(CLIENT_NAME));
        if (sid != null && !sid.isEmpty()) {
            q.append("&sid=").append(enc(sid));
        }
        return q.toString();
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private VesperaHttp.Response exchange(Network network, String host, String method,
            String path, String body, String cookie, int timeoutMs) throws IOException {
        Socket socket = VesperaSockets.create(network);
        current = socket;
        try {
            socket.connect(new InetSocketAddress(host, PORT), Math.min(timeoutMs, 8_000));
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            byte[] raw = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.0\r\n");
            head.append("Host: ").append(host).append(':').append(PORT).append("\r\n");
            head.append("Accept: */*\r\n");
            head.append("Connection: close\r\n");
            if (cookie != null && !cookie.isEmpty()) {
                head.append("Cookie: ").append(cookie).append("\r\n");
            }
            if (raw != null) {
                head.append("Content-Type: text/plain;charset=UTF-8\r\n");
                head.append("Content-Length: ").append(raw.length).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (raw != null) out.write(raw);
            out.flush();
            return readResponse(socket.getInputStream());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (current == socket) current = null;
        }
    }

    private static VesperaHttp.Response readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
            if (buf.size() > 700_000) break;
        }
        byte[] raw = buf.toByteArray();
        if (raw.length == 0) throw new IOException("empty HTTP response");
        String latin = new String(raw, StandardCharsets.ISO_8859_1);
        int split = latin.indexOf("\r\n\r\n");
        int skip = 4;
        if (split < 0) {
            split = latin.indexOf("\n\n");
            skip = 2;
        }
        if (split < 0) throw new IOException("HTTP headers");
        String headers = latin.substring(0, split);
        String body = new String(raw, split + skip, raw.length - split - skip, StandardCharsets.UTF_8);
        int lineEnd = headers.indexOf('\n');
        String line = (lineEnd < 0 ? headers : headers.substring(0, lineEnd)).trim();
        int space = line.indexOf(' ');
        int end = space + 1;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        int code = Integer.parseInt(line.substring(space + 1, end));
        return new VesperaHttp.Response(code, body);
    }

    private void closeCurrent() {
        Socket s = current;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleepQuiet(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
