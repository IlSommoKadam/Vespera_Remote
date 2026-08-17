package com.vaonis.vesperahelper;

import android.net.Network;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Minimal FTP client (PASV) bound to the Vespera {@link Network} when available. */
public final class SimpleFtpClient implements Closeable {
    public static final class Entry {
        public final String path;
        public final String name;
        public final boolean directory;
        public final long size;

        Entry(String path, String name, boolean directory, long size) {
            this.path = path;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private final Network network;
    private final String host;
    private Socket control;
    private BufferedReader in;
    private BufferedWriter out;

    public SimpleFtpClient(Network network, String host) {
        this.network = network;
        this.host = host;
    }

    public void connect(int port) throws IOException {
        control = openSocket(host, port, 8_000);
        control.setSoTimeout(20_000);
        in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        expect(readResponse(), 220);
        loginAnonymous();
        send("TYPE I");
        expect(readResponse(), 200, 220);
    }

    public List<Entry> listRecursive(String rootPath) throws IOException {
        List<Entry> files = new ArrayList<>();
        walk(normalizeDir(rootPath), files);
        return files;
    }

    public long sizeOf(String path) {
        try {
            send("SIZE " + path);
            String resp = readResponse();
            if (code(resp) == 213) {
                String[] parts = resp.trim().split("\\s+");
                if (parts.length >= 2) return Long.parseLong(parts[1].trim());
            }
        } catch (Exception ignored) {
            return -1;
        }
        return -1;
    }

    /**
     * Downloads {@code remotePath} to {@code localFile}. Returns CRC32 of the bytes written.
     * Leaves a {@code .part} file if the transfer is incomplete.
     */
    public long retrieve(String remotePath, File localFile) throws IOException {
        File parent = localFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir " + parent);
        }
        File part = new File(localFile.getAbsolutePath() + ".part");
        send("PASV");
        Pasv pasv = parsePasv(readResponse());
        send("RETR " + remotePath);
        try (Socket data = openSocket(pasv.host, pasv.port, 15_000);
             InputStream raw = data.getInputStream();
             FileOutputStream fos = new FileOutputStream(part)) {
            data.setSoTimeout(180_000);
            String open = readResponse();
            int opened = code(open);
            if (opened != 150 && opened != 125) {
                throw new IOException("RETR " + remotePath + ": " + open);
            }
            CRC32 crc = new CRC32();
            byte[] buf = new byte[65_536];
            int n;
            while ((n = raw.read(buf)) >= 0) {
                if (n == 0) continue;
                fos.write(buf, 0, n);
                crc.update(buf, 0, n);
            }
            fos.flush();
            String done = readResponse();
            if (code(done) != 226 && code(done) != 250) {
                throw new IOException("RETR incomplete " + remotePath + ": " + done);
            }
            if (localFile.exists() && !localFile.delete()) {
                throw new IOException("replace " + localFile);
            }
            if (!part.renameTo(localFile)) {
                throw new IOException("rename " + part);
            }
            return crc.getValue();
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw failure;
        }
    }

    public void deleteFile(String remotePath) throws IOException {
        send("DELE " + remotePath);
        int c = code(readResponse());
        if (c != 250 && c != 200) throw new IOException("DELE " + remotePath);
    }

    public void removeDir(String remotePath) {
        try {
            send("RMD " + remotePath);
            readResponse();
        } catch (IOException ignored) {
            // Best-effort cleanup of empty observation folders.
        }
    }

    public boolean cwd(String path) throws IOException {
        send("CWD " + path);
        int c = code(readResponse());
        return c == 250 || c == 200;
    }

    @Override public void close() {
        try {
            if (out != null) {
                send("QUIT");
                readResponse();
            }
        } catch (Exception ignored) {
        }
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (control != null) control.close(); } catch (Exception ignored) {}
    }

    private void loginAnonymous() throws IOException {
        String[][] attempts = {
                {"anonymous", "vespera@helper"},
                {"anonymous", ""},
                {"ftp", "ftp"}
        };
        IOException last = null;
        for (String[] pair : attempts) {
            send("USER " + pair[0]);
            int userCode = code(readResponse());
            if (userCode == 230) return;
            if (userCode != 331) continue;
            send("PASS " + pair[1]);
            int passCode = code(readResponse());
            if (passCode == 230 || passCode == 202) return;
            last = new IOException("FTP login failed: " + passCode);
        }
        throw last != null ? last : new IOException("FTP login failed");
    }

    private void walk(String dir, List<Entry> files) throws IOException {
        if (!cwd(dir)) {
            if (CommonsFtpClient.isUserDirName(dir)) {
                throw new IOException("missing-user");
            }
            return;
        }
        List<Entry> entries = listCurrent(dir);
        for (Entry entry : entries) {
            if (entry.directory) {
                walk(entry.path, files);
            } else {
                files.add(entry);
            }
        }
    }

    private List<Entry> listCurrent(String dir) throws IOException {
        send("PASV");
        Pasv pasv = parsePasv(readResponse());
        send("LIST");
        StringBuilder listing = new StringBuilder();
        try (Socket data = openSocket(pasv.host, pasv.port, 15_000);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new BufferedInputStream(data.getInputStream()),
                             StandardCharsets.UTF_8))) {
            data.setSoTimeout(60_000);
            String open = readResponse();
            int opened = code(open);
            if (opened != 150 && opened != 125) {
                throw new IOException("LIST " + dir + ": " + open);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                listing.append(line).append('\n');
            }
        }
        String done = readResponse();
        if (code(done) != 226 && code(done) != 250) {
            throw new IOException("LIST incomplete: " + done);
        }
        return parseList(dir, listing.toString());
    }

    static List<Entry> parseList(String dir, String listing) {
        List<Entry> out = new ArrayList<>();
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        for (String raw : listing.split("\n")) {
            String line = raw.replace("\r", "").trim();
            if (line.isEmpty() || line.toLowerCase(Locale.US).startsWith("total ")) continue;
            Entry parsed = parseListLine(prefix, line);
            if (parsed == null) continue;
            if (".".equals(parsed.name) || "..".equals(parsed.name)) continue;
            out.add(parsed);
        }
        return out;
    }

    private static Entry parseListLine(String prefix, String line) {
        if (line.startsWith("d") || line.startsWith("-") || line.startsWith("l")) {
            String[] parts = line.split("\\s+", 9);
            if (parts.length < 9) return null;
            boolean dir = line.charAt(0) == 'd' || (line.charAt(0) == 'l' && line.contains("->")
                    && !line.contains("."));
            long size = 0;
            try { size = Long.parseLong(parts[4]); } catch (NumberFormatException ignored) {}
            String name = parts[8];
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) name = name.substring(0, arrow);
            return new Entry(prefix + name, name, dir, size);
        }
        String upper = line.toUpperCase(Locale.US);
        if (upper.contains("<DIR>")) {
            String name = line.substring(upper.indexOf("<DIR>") + 5).trim();
            if (name.isEmpty()) return null;
            return new Entry(prefix + name, name, true, 0);
        }
        // DOS: 01-01-26  12:00PM              12345 filename
        String[] dos = line.split("\\s+");
        if (dos.length >= 4) {
            try {
                long size = Long.parseLong(dos[2]);
                String name = line.substring(line.lastIndexOf(dos[3])).trim();
                if (dos.length > 4) {
                    int idx = indexOfNthToken(line, 3);
                    if (idx >= 0) name = line.substring(idx).trim();
                }
                return new Entry(prefix + name, name, false, size);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int indexOfNthToken(String line, int n) {
        int seen = 0;
        boolean in = false;
        for (int i = 0; i < line.length(); i++) {
            boolean ws = Character.isWhitespace(line.charAt(i));
            if (!ws && !in) {
                if (seen == n) return i;
                seen++;
                in = true;
            } else if (ws) {
                in = false;
            }
        }
        return -1;
    }

    private void send(String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    private String readResponse() throws IOException {
        String first = in.readLine();
        if (first == null) throw new IOException("FTP closed");
        StringBuilder all = new StringBuilder(first);
        if (first.length() >= 4 && first.charAt(3) == '-') {
            String prefix = first.substring(0, 3) + " ";
            while (true) {
                String next = in.readLine();
                if (next == null) throw new IOException("FTP closed");
                all.append('\n').append(next);
                if (next.startsWith(prefix)) break;
            }
        }
        return all.toString();
    }

    private static int code(String response) {
        if (response == null || response.length() < 3) return 0;
        try {
            return Integer.parseInt(response.substring(0, 3));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void expect(String response, int... codes) throws IOException {
        int got = code(response);
        for (int code : codes) {
            if (got == code) return;
        }
        throw new IOException("FTP unexpected " + response);
    }

    private Pasv parsePasv(String response) throws IOException {
        if (code(response) != 227) throw new IOException("PASV: " + response);
        int start = response.indexOf('(');
        int end = response.indexOf(')', start + 1);
        if (start < 0 || end < 0) throw new IOException("PASV parse: " + response);
        String[] p = response.substring(start + 1, end).split(",");
        if (p.length < 6) throw new IOException("PASV fields: " + response);
        String pasvHost = p[0].trim() + "." + p[1].trim() + "." + p[2].trim() + "." + p[3].trim();
        int port = Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
        if (pasvHost.startsWith("127.") || pasvHost.startsWith("0.")) pasvHost = host;
        return new Pasv(pasvHost, port);
    }

    private Socket openSocket(String destHost, int destPort, int timeoutMs) throws IOException {
        Socket socket = VesperaSockets.create(network);
        socket.connect(new InetSocketAddress(destHost, destPort), timeoutMs);
        return socket;
    }

    private static String normalizeDir(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) return "/" + path;
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }

    private static final class Pasv {
        final String host;
        final int port;
        Pasv(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
