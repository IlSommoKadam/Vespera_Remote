package com.vaonis.vesperahelper;

import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal HTTP/1.0 over {@link VesperaSockets}. Avoids {@code Network.openConnection},
 * which does not fall back to the daemon {@code 10.0.0.0/24} route when Android
 * denies a bind ({@code EPERM}) on the Vespera AP.
 */
final class VesperaHttp {
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private VesperaHttp() {}

    static Response get(Network network, String host, int port, String path, int timeoutMs)
            throws IOException {
        return request(network, host, port, "GET", path, null, null, null, null, timeoutMs);
    }

    static Response get(Network network, String host, int port, String path, String cookie,
            int timeoutMs) throws IOException {
        return request(network, host, port, "GET", path, null, null, null, cookie, timeoutMs);
    }

    static Response post(Network network, String host, int port, String path, String jsonBody,
            String authorization, int timeoutMs) throws IOException {
        return post(network, host, port, path, jsonBody, authorization, timeoutMs, false);
    }

    static Response post(Network network, String host, int port, String path, String jsonBody,
            String authorization, int timeoutMs, boolean acceptHangup) throws IOException {
        byte[] body = (jsonBody == null ? "{}" : jsonBody).getBytes(StandardCharsets.UTF_8);
        return request(network, host, port, "POST", path, "application/json", body,
                authorization, null, timeoutMs, acceptHangup);
    }

    static Response postPlain(Network network, String host, int port, String path, String body,
            String cookie, int timeoutMs) throws IOException {
        byte[] raw = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        return request(network, host, port, "POST", path, "text/plain;charset=UTF-8", raw,
                null, cookie, timeoutMs);
    }

    static boolean portOpen(Network network, String host, int port, int timeoutMs) {
        try (Socket socket = VesperaSockets.create(network)) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Response request(Network network, String host, int port, String method,
            String path, String contentType, byte[] body, String authorization, String cookie,
            int timeoutMs)
            throws IOException {
        return request(network, host, port, method, path, contentType, body, authorization,
                cookie, timeoutMs, false);
    }

    private static Response request(Network network, String host, int port, String method,
            String path, String contentType, byte[] body, String authorization, String cookie,
            int timeoutMs, boolean acceptHangup)
            throws IOException {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        if (path == null || path.isEmpty()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        Socket socket = VesperaSockets.create(network);
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.0\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("Accept: application/json, */*\r\n");
            head.append("Connection: close\r\n");
            if (authorization != null && !authorization.isEmpty()) {
                head.append("Authorization: ").append(authorization).append("\r\n");
            }
            if (cookie != null && !cookie.isEmpty()) {
                head.append("Cookie: ").append(cookie).append("\r\n");
            }
            if (body != null && body.length > 0) {
                String type = contentType == null ? "application/json" : contentType;
                head.append("Content-Type: ").append(type).append("\r\n");
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null && body.length > 0) out.write(body);
            out.flush();
            if (acceptHangup) {
                int readMs = Math.min(timeoutMs, 2_500);
                socket.setSoTimeout(Math.max(500, readMs));
                try {
                    return readResponse(socket.getInputStream());
                } catch (IOException hangup) {
                    if (isHangup(hangup)) return new Response(0, "");
                    throw hangup;
                }
            }
            return readResponse(socket.getInputStream());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Response readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[2048];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
            if (buf.size() > 512_000) break;
        }
        byte[] raw = buf.toByteArray();
        if (raw.length == 0) throw new IOException("empty HTTP response");
        String latin = new String(raw, StandardCharsets.ISO_8859_1);
        int split = headerSplit(latin);
        if (split < 0) throw new IOException("HTTP headers");
        String headers = latin.substring(0, split);
        String body = new String(raw, split, raw.length - split, StandardCharsets.UTF_8);
        int code = parseStatusCode(headers);
        if (code <= 0) throw new IOException("HTTP status");
        int contentLength = parseContentLength(headers);
        if (contentLength >= 0 && contentLength < body.length()) {
            body = body.substring(0, contentLength);
        }
        return new Response(code, body);
    }

    static boolean isHangup(Exception failure) {
        if (failure == null) return false;
        if (failure instanceof SocketTimeoutException) return true;
        String msg = failure.getMessage();
        if (msg == null) msg = failure.getClass().getSimpleName();
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("reset")
                || lower.contains("broken pipe")
                || lower.contains("connection abort")
                || lower.contains("empty http")
                || lower.contains("econnreset")
                || lower.contains("etimedout");
    }

    private static int headerSplit(String text) {
        int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) return crlf + 4;
        int lf = text.indexOf("\n\n");
        if (lf >= 0) return lf + 2;
        return -1;
    }

    private static int parseStatusCode(String headers) {
        int lineEnd = headers.indexOf('\n');
        String line = lineEnd < 0 ? headers : headers.substring(0, lineEnd);
        line = line.trim();
        int space = line.indexOf(' ');
        if (space < 0 || space + 1 >= line.length()) return -1;
        int end = space + 1;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        if (end == space + 1) return -1;
        try {
            return Integer.parseInt(line.substring(space + 1, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parseContentLength(String headers) {
        String[] lines = headers.split("\r?\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (!"content-length".equals(line.substring(0, colon).trim().toLowerCase(Locale.US))) {
                continue;
            }
            try {
                return Integer.parseInt(line.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
