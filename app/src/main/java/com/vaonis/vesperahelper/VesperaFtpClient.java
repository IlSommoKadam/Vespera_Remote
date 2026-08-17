package com.vaonis.vesperahelper;

import android.net.Network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
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

/**
 * Minimal FTP client for the Vespera (typically 10.0.0.1:21, USER folder).
 * All sockets are bound to the Vespera Wi-Fi {@link Network}.
 */
final class VesperaFtpClient implements Closeable {
    static final String HOST = "10.0.0.1";
    static final int PORT = 21;
    static final String USER_DIR = "USER";

    private final Network network;
    private Socket control;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String lastReply = "";

    VesperaFtpClient(Network network) {
        this.network = network;
    }

    void connect() throws IOException {
        control = VesperaSockets.create(network);
        control.connect(new InetSocketAddress(HOST, PORT), 8_000);
        control.setSoTimeout(30_000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        expect(220);
        if (!login("anonymous", "vespera@helper")
                && !login("anonymous", "")
                && !login("ftp", "ftp")) {
            throw new IOException("FTP login failed: " + lastReply);
        }
        command("TYPE I");
        expect(200, 250);
    }

    private boolean login(String user, String pass) throws IOException {
        command("USER " + user);
        int code = replyCode();
        if (code == 230) return true;
        if (code != 331) return false;
        command("PASS " + pass);
        return replyCode() == 230;
    }

    List<RemoteFile> listUserTree() throws IOException {
        String root = enterUserDir();
        List<RemoteFile> files = new ArrayList<>();
        walk(root, "", files);
        return files;
    }

    private String enterUserDir() throws IOException {
        String[] candidates = {"/user", "/USER", "user", "USER"};
        for (String candidate : candidates) {
            command("CWD " + candidate);
            int code = replyCode();
            if (code >= 200 && code < 300) {
                return candidate.startsWith("/") ? candidate : "/" + candidate;
            }
        }
        throw new IOException("USER dir not found: " + lastReply);
    }

    private void walk(String remoteDir, String rel, List<RemoteFile> out) throws IOException {
        for (Entry entry : list(remoteDir)) {
            if (".".equals(entry.name) || "..".equals(entry.name)) continue;
            String childRel = rel.isEmpty() ? entry.name : rel + "/" + entry.name;
            String childRemote = joinRemote(remoteDir, entry.name);
            if (entry.directory) {
                walk(childRemote, childRel, out);
            } else {
                long size = entry.size;
                if (size < 0) size = sizeOf(childRemote);
                out.add(new RemoteFile(childRemote, childRel, size));
            }
        }
    }

    DownloadResult retrieve(RemoteFile file, File local) throws IOException {
        File parent = local.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent.getAbsolutePath());
        }
        File partial = new File(local.getAbsolutePath() + ".partial");
        CRC32 crc = new CRC32();
        long written = 0;
        Socket data = openPasv();
        try {
            command("RETR " + file.remotePath);
            int code = replyCode();
            if (code != 150 && code != 125) {
                throw new IOException("RETR failed: " + lastReply);
            }
            try (InputStream in = data.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    crc.update(buf, 0, n);
                    written += n;
                }
                out.getFD().sync();
            }
            readReply();
            expect(226, 250);
        } finally {
            quietClose(data);
        }
        if (file.size >= 0 && written != file.size) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw new IOException("size mismatch " + file.relativePath
                    + " remote=" + file.size + " local=" + written);
        }
        if (local.exists() && !local.delete()) {
            throw new IOException("cannot replace " + local.getAbsolutePath());
        }
        if (!partial.renameTo(local)) {
            throw new IOException("rename failed: " + partial.getAbsolutePath());
        }
        return new DownloadResult(written, crc.getValue());
    }

    void delete(RemoteFile file) throws IOException {
        command("DELE " + file.remotePath);
        int code = replyCode();
        if (code >= 200 && code < 300) return;
        throw new IOException("DELE failed: " + lastReply);
    }

    private long sizeOf(String remotePath) throws IOException {
        command("SIZE " + remotePath);
        if (replyCode() != 213) return -1;
        String[] parts = lastReply.trim().split("\\s+");
        if (parts.length < 2) return -1;
        try {
            return Long.parseLong(parts[1].trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private List<Entry> list(String remoteDir) throws IOException {
        Socket data = openPasv();
        List<String> lines = new ArrayList<>();
        try {
            command("LIST " + remoteDir);
            int code = replyCode();
            if (code != 150 && code != 125) {
                quietClose(data);
                throw new IOException("LIST failed: " + lastReply);
            }
            try (BufferedReader body = new BufferedReader(
                    new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = body.readLine()) != null) lines.add(line);
            }
            readReply();
            expect(226, 250);
        } finally {
            quietClose(data);
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : lines) {
            Entry parsed = parseListLine(line);
            if (parsed != null) entries.add(parsed);
        }
        return entries;
    }

    private Socket openPasv() throws IOException {
        command("PASV");
        expect(227);
        int start = lastReply.indexOf('(');
        int end = lastReply.indexOf(')');
        if (start < 0 || end <= start) throw new IOException("bad PASV: " + lastReply);
        String[] p = lastReply.substring(start + 1, end).split(",");
        if (p.length < 6) throw new IOException("bad PASV: " + lastReply);
        String ip = p[0].trim() + "." + p[1].trim() + "." + p[2].trim() + "." + p[3].trim();
        int port = Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
        if (ip.startsWith("0.") || ip.startsWith("127.")) ip = HOST;
        Socket data = VesperaSockets.create(network);
        data.connect(new InetSocketAddress(ip, port), 8_000);
        data.setSoTimeout(120_000);
        return data;
    }

    private void command(String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
        readReply();
    }

    private void expect(int... codes) throws IOException {
        int got = replyCode();
        for (int code : codes) {
            if (got == code) return;
        }
        throw new IOException("FTP expected " + join(codes) + " got: " + lastReply);
    }

    private int replyCode() {
        if (lastReply.length() < 3) return 0;
        try {
            return Integer.parseInt(lastReply.substring(0, 3));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void readReply() throws IOException {
        String last = null;
        while (true) {
            String line = reader.readLine();
            if (line == null) throw new EOFException("FTP control closed");
            last = line;
            if (line.length() >= 4
                    && Character.isDigit(line.charAt(0))
                    && Character.isDigit(line.charAt(1))
                    && Character.isDigit(line.charAt(2))
                    && line.charAt(3) == ' ') {
                break;
            }
        }
        lastReply = last;
    }

    @Override public void close() {
        try {
            if (writer != null) {
                writer.write("QUIT\r\n");
                writer.flush();
            }
        } catch (IOException ignored) {
        }
        quietClose(control);
        control = null;
    }

    private static void quietClose(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static String joinRemote(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private static String join(int... codes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) b.append('/');
            b.append(codes[i]);
        }
        return b.toString();
    }

    static Entry parseListLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.US).startsWith("total ")) return null;
        if (trimmed.startsWith("d") || trimmed.startsWith("-") || trimmed.startsWith("l")) {
            String[] parts = trimmed.split("\\s+", 9);
            if (parts.length < 9) return null;
            String name = parts[8];
            if (name.contains(" -> ")) name = name.substring(0, name.indexOf(" -> "));
            boolean dir = trimmed.charAt(0) == 'd';
            long size = -1;
            try { size = Long.parseLong(parts[4]); } catch (NumberFormatException ignored) {}
            return new Entry(name, dir, size);
        }
        if (trimmed.contains("<DIR>")) {
            String name = trimmed.substring(trimmed.indexOf("<DIR>") + 5).trim();
            if (name.isEmpty()) return null;
            return new Entry(name, true, -1);
        }
        return null;
    }

    static final class Entry {
        final String name;
        final boolean directory;
        final long size;
        Entry(String name, boolean directory, long size) {
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    static final class RemoteFile {
        final String remotePath;
        final String relativePath;
        final long size;
        RemoteFile(String remotePath, String relativePath, long size) {
            this.remotePath = remotePath;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    static final class DownloadResult {
        final long bytes;
        final long crc32;
        DownloadResult(long bytes, long crc32) {
            this.bytes = bytes;
            this.crc32 = crc32;
        }
    }
}
