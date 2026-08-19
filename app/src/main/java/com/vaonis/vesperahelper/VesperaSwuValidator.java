package com.vaonis.vesperahelper;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a Vespera SWUpdate ({@code vespera-*.swu}) archive:
 * CPIO newc/crc magic, {@code sw-description}, and per-file SHA-256.
 */
final class VesperaSwuValidator {
    private static final int HEADER_LEN = 110;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_NAME = 4096;
    private static final int MAX_DESC = 1_000_000;
    private static final String HEX = "0123456789abcdef";
    private static final Pattern SHA256_FIELD =
            Pattern.compile("sha256\\s*=\\s*\"([0-9a-fA-F]{64})\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_FIELD =
            Pattern.compile("filename\\s*=\\s*\"([^\"]+)\"");

    enum Kind {
        OK, BAD_NAME, EMPTY, BAD_MAGIC, BAD_CPIO, MISSING_DESC, HASH_MISMATCH, IO
    }

    static final class Result {
        final Kind kind;
        final String sha256;
        final int filesChecked;
        final String detail;
        final long size;
        final String name;

        Result(Kind kind, String sha256, int filesChecked, String detail, long size, String name) {
            this.kind = kind;
            this.sha256 = sha256 == null ? "" : sha256;
            this.filesChecked = filesChecked;
            this.detail = detail == null ? "" : detail;
            this.size = size;
            this.name = name == null ? "" : name;
        }

        boolean ok() {
            return kind == Kind.OK;
        }
    }

    private VesperaSwuValidator() {}

    static boolean isValidSwuName(String name) {
        if (name == null) return false;
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        String lower = n.toLowerCase(Locale.US);
        return lower.startsWith("vespera-") && lower.endsWith(".swu") && n.length() > 12;
    }

    static Result inspect(File file) {
        if (file == null || !file.isFile()) {
            return new Result(Kind.IO, "", 0, "not-a-file", 0, "");
        }
        String name = file.getName();
        long size = file.length();
        if (!isValidSwuName(name)) {
            return new Result(Kind.BAD_NAME, "", 0, name, size, name);
        }
        if (size <= 0) {
            return new Result(Kind.EMPTY, "", 0, name, size, name);
        }
        String wholeSha;
        try {
            wholeSha = sha256File(file);
        } catch (Exception e) {
            return new Result(Kind.IO, "", 0, message(e), size, name);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65_536)) {
            CpioParse parsed = parseCpio(in);
            if (parsed.kind != Kind.OK) {
                return new Result(parsed.kind, wholeSha, 0, parsed.detail, size, name);
            }
            Map<String, String> expected = parseExpectedHashes(parsed.description);
            if (expected.isEmpty()) {
                return new Result(Kind.MISSING_DESC, wholeSha, 0, "no-sha256", size, name);
            }
            int checked = 0;
            for (Map.Entry<String, String> e : expected.entrySet()) {
                String got = parsed.fileSha256.get(e.getKey());
                if (got == null) {
                    return new Result(Kind.HASH_MISMATCH, wholeSha, checked,
                            e.getKey(), size, name);
                }
                if (!got.equals(e.getValue())) {
                    return new Result(Kind.HASH_MISMATCH, wholeSha, checked,
                            e.getKey(), size, name);
                }
                checked++;
            }
            return new Result(Kind.OK, wholeSha, checked, "", size, name);
        } catch (EOFException e) {
            return new Result(Kind.BAD_CPIO, wholeSha, 0, "truncated", size, name);
        } catch (Exception e) {
            return new Result(Kind.IO, wholeSha, 0, message(e), size, name);
        }
    }

    private static final class CpioParse {
        final Kind kind;
        final String description;
        final String detail;
        final Map<String, String> fileSha256;

        CpioParse(Kind kind, String description, String detail, Map<String, String> fileSha256) {
            this.kind = kind;
            this.description = description == null ? "" : description;
            this.detail = detail == null ? "" : detail;
            this.fileSha256 = fileSha256 == null
                    ? new LinkedHashMap<String, String>()
                    : fileSha256;
        }
    }

    private static CpioParse parseCpio(InputStream in) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        String description = null;
        boolean sawTrailer = false;
        byte[] header = new byte[HEADER_LEN];
        for (int i = 0; i < MAX_ENTRIES; i++) {
            readFully(in, header, 0, HEADER_LEN);
            String magic = new String(header, 0, 6, StandardCharsets.US_ASCII);
            if (!"070701".equals(magic) && !"070702".equals(magic)) {
                if (i == 0) return new CpioParse(Kind.BAD_MAGIC, "", magic, hashes);
                return new CpioParse(Kind.BAD_CPIO, description, "magic:" + magic, hashes);
            }
            long filesize = parseHex(header, 54, 8);
            int namesize = (int) parseHex(header, 94, 8);
            if (namesize <= 0 || namesize > MAX_NAME || filesize < 0) {
                return new CpioParse(Kind.BAD_CPIO, description, "header", hashes);
            }
            byte[] nameBytes = new byte[namesize];
            readFully(in, nameBytes, 0, namesize);
            skipFully(in, pad4(HEADER_LEN + namesize));
            String fname = cString(nameBytes);
            if (fname.startsWith("./")) fname = fname.substring(2);
            int slash = fname.lastIndexOf('/');
            if (slash >= 0) fname = fname.substring(slash + 1);

            if ("TRAILER!!!".equals(fname)) {
                skipFully(in, filesize + pad4(filesize));
                sawTrailer = true;
                break;
            }
            if ("sw-description".equals(fname)) {
                if (filesize > MAX_DESC) {
                    return new CpioParse(Kind.BAD_CPIO, "", "desc-too-large", hashes);
                }
                byte[] body = new byte[(int) filesize];
                readFully(in, body, 0, body.length);
                skipFully(in, pad4(filesize));
                description = new String(body, StandardCharsets.UTF_8);
                continue;
            }
            String sha = sha256Limited(in, filesize);
            skipFully(in, pad4(filesize));
            if (!fname.isEmpty()) hashes.put(fname, sha);
        }
        if (!sawTrailer) {
            return new CpioParse(Kind.BAD_CPIO, description, "no-trailer", hashes);
        }
        if (description == null || description.isEmpty()) {
            return new CpioParse(Kind.MISSING_DESC, "", "no-sw-description", hashes);
        }
        return new CpioParse(Kind.OK, description, "", hashes);
    }

    private static Map<String, String> parseExpectedHashes(String desc) {
        Map<String, String> out = new LinkedHashMap<>();
        if (desc == null) return out;
        Matcher sha = SHA256_FIELD.matcher(desc);
        while (sha.find()) {
            int from = Math.max(0, sha.start() - 400);
            String window = desc.substring(from, sha.start());
            Matcher files = FILENAME_FIELD.matcher(window);
            String filename = null;
            while (files.find()) filename = files.group(1);
            if (filename == null || filename.isEmpty()) continue;
            out.put(filename, sha.group(1).toLowerCase(Locale.US));
        }
        return out;
    }

    private static String sha256File(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65_536)) {
            byte[] buf = new byte[65_536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String sha256Limited(InputStream in, long n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65_536];
        long left = n;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, want);
            if (r < 0) throw new EOFException();
            md.update(buf, 0, r);
            left -= r;
        }
        return hex(md.digest());
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException();
            got += n;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped > 0) {
                n -= skipped;
                continue;
            }
            if (in.read() < 0) throw new EOFException();
            n--;
        }
    }

    private static long pad4(long n) {
        long rem = n % 4;
        return rem == 0 ? 0 : 4 - rem;
    }

    private static long parseHex(byte[] buf, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            int c = buf[off + i] & 0xFF;
            int d;
            if (c >= '0' && c <= '9') d = c - '0';
            else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
            else throw new NumberFormatException("hex");
            v = (v << 4) | d;
        }
        return v;
    }

    private static String cString(byte[] raw) {
        int end = 0;
        while (end < raw.length && raw[end] != 0) end++;
        return new String(raw, 0, end, StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX.charAt(v >>> 4);
            out[i * 2 + 1] = HEX.charAt(v & 0x0F);
        }
        return new String(out);
    }

    private static String message(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.toString() : m;
    }
}
