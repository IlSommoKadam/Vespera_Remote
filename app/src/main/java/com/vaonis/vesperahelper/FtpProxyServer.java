package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local FTP listener that proxies to the Vespera control port, rewriting PASV
 * so Tailscale / Ethernet clients can fetch live instrument files.
 */
final class FtpProxyServer {
    private static final String TAG = "VesperaFtpProxy";
    private static final Pattern PASV = Pattern.compile(
            "\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern EPSV = Pattern.compile("\\|\\|\\|(\\d+)\\|");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile ExecutorService pool;
    private volatile Network network;
    private volatile String upstreamHost = PhotoSyncEngine.HOST;
    private volatile int upstreamPort = 21;
    private volatile int listenPort = FtpProbe.TELESCOPE_PREFERRED;

    synchronized void start(Network network, String host, int upPort, int avoidListen)
            throws IOException {
        if (running.get() && server != null && !server.isClosed()
                && this.upstreamPort == upPort && host.equals(this.upstreamHost)) {
            return;
        }
        stop();
        this.network = network;
        this.upstreamHost = host;
        this.upstreamPort = upPort;
        pool = Executors.newCachedThreadPool();
        server = new ServerSocket();
        server.setReuseAddress(true);
        listenPort = FtpProbe.bindPreferred(server, FtpProbe.TELESCOPE_PREFERRED, avoidListen);
        running.set(true);
        Thread accept = new Thread(this::acceptLoop, "vespera-ftp-proxy");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "proxy :" + listenPort + " -> " + host + ":" + upPort);
    }

    synchronized void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    boolean isRunning() {
        return running.get() && server != null && !server.isClosed();
    }

    int getPort() {
        return listenPort;
    }

    int getUpstreamPort() {
        return upstreamPort;
    }

    String getUpstreamHost() {
        return upstreamHost;
    }

    private void acceptLoop() {
        ServerSocket listen = server;
        while (running.get() && listen != null && !listen.isClosed()) {
            try {
                Socket client = listen.accept();
                ExecutorService workers = pool;
                if (workers == null) {
                    client.close();
                    return;
                }
                workers.execute(() -> handle(client));
            } catch (SocketException closed) {
                break;
            } catch (IOException failure) {
                if (running.get()) Log.w(TAG, "accept", failure);
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(120_000);
            upstream = VesperaSockets.create(network);
            upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 8_000);
            upstream.setSoTimeout(120_000);
            Session session = new Session(client, upstream);
            Thread down = new Thread(session::clientToUpstream, "ftp-proxy-c2u");
            down.setDaemon(true);
            down.start();
            session.upstreamToClient();
            down.join(1_000);
        } catch (Exception failure) {
            Log.w(TAG, "session", failure);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
            if (upstream != null) {
                try { upstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private final class Session {
        private final Socket client;
        private final Socket upstream;
        private final Object clientOut = new Object();
        private volatile ServerSocket pasvListen;
        private volatile Socket pasvUpstream;

        Session(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        void clientToUpstream() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                OutputStreamWriter writer = new OutputStreamWriter(
                        upstream.getOutputStream(), StandardCharsets.UTF_8);
                String line;
                while ((line = reader.readLine()) != null) {
                    String upper = line.trim().toUpperCase();
                    int sp = upper.indexOf(' ');
                    String cmd = sp > 0 ? upper.substring(0, sp) : upper;
                    if ("PORT".equals(cmd) || "EPRT".equals(cmd)) {
                        writeClient("500 Use PASV\r\n");
                        continue;
                    }
                    writer.write(line);
                    writer.write("\r\n");
                    writer.flush();
                }
            } catch (Exception ignored) {
            }
        }

        void upstreamToClient() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(upstream.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean more = line.length() >= 4 && line.charAt(3) == '-';
                    block.append(line).append("\r\n");
                    if (more) continue;
                    String reply = rewritePasv(block.toString());
                    block.setLength(0);
                    writeClient(reply);
                }
            } catch (Exception ignored) {
            } finally {
                closePasv();
            }
        }

        private String rewritePasv(String reply) throws IOException {
            if (reply.startsWith("227")) {
                Matcher match = PASV.matcher(reply);
                if (!match.find()) return reply;
                String host = match.group(1) + "." + match.group(2) + "."
                        + match.group(3) + "." + match.group(4);
                int port = Integer.parseInt(match.group(5)) * 256
                        + Integer.parseInt(match.group(6));
                if (host.startsWith("127.") || host.startsWith("0.")) host = upstreamHost;
                int local = openDataBridge(host, port);
                if (local <= 0) return "425 Data connection failed\r\n";
                InetAddress addr = client.getLocalAddress();
                byte[] a = addr == null ? null : addr.getAddress();
                if (a == null || a.length != 4) return "522 Use EPSV\r\n";
                return "227 Entering Passive Mode ("
                        + (a[0] & 0xff) + "," + (a[1] & 0xff) + ","
                        + (a[2] & 0xff) + "," + (a[3] & 0xff) + ","
                        + (local / 256) + "," + (local % 256) + ")\r\n";
            }
            if (reply.startsWith("229")) {
                Matcher match = EPSV.matcher(reply);
                if (!match.find()) return reply;
                int port = Integer.parseInt(match.group(1));
                int local = openDataBridge(upstreamHost, port);
                if (local <= 0) return "425 Data connection failed\r\n";
                return "229 Entering Extended Passive Mode (|||" + local + "|)\r\n";
            }
            return reply;
        }

        private int openDataBridge(String host, int port) {
            closePasv();
            try {
                pasvUpstream = VesperaSockets.create(network);
                pasvUpstream.connect(new InetSocketAddress(host, port), 8_000);
                pasvUpstream.setSoTimeout(180_000);
                InetAddress local = client.getLocalAddress();
                pasvListen = new ServerSocket();
                pasvListen.setReuseAddress(true);
                pasvListen.bind(new InetSocketAddress(local, 0));
                pasvListen.setSoTimeout(30_000);
                final ServerSocket listen = pasvListen;
                final Socket upData = pasvUpstream;
                Thread bridge = new Thread(() -> {
                    try (Socket down = listen.accept()) {
                        down.setSoTimeout(180_000);
                        pump(down, upData);
                    } catch (Exception failure) {
                        Log.w(TAG, "data", failure);
                    } finally {
                        try { upData.close(); } catch (Exception ignored) {}
                        try { listen.close(); } catch (Exception ignored) {}
                    }
                }, "ftp-proxy-data");
                bridge.setDaemon(true);
                bridge.start();
                return pasvListen.getLocalPort();
            } catch (Exception failure) {
                Log.w(TAG, "openDataBridge", failure);
                closePasv();
                return -1;
            }
        }

        private void writeClient(String text) throws IOException {
            synchronized (clientOut) {
                OutputStreamWriter writer = new OutputStreamWriter(
                        client.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(text);
                writer.flush();
            }
        }

        private void closePasv() {
            try { if (pasvListen != null) pasvListen.close(); } catch (Exception ignored) {}
            pasvListen = null;
            try { if (pasvUpstream != null) pasvUpstream.close(); } catch (Exception ignored) {}
            pasvUpstream = null;
        }
    }

    private static void pump(Socket a, Socket b) {
        Thread t = new Thread(() -> copy(a, b), "ftp-proxy-ab");
        t.setDaemon(true);
        t.start();
        copy(b, a);
        try { t.join(1_000); } catch (InterruptedException ignored) {}
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (Exception ignored) {}
        }
    }
}
