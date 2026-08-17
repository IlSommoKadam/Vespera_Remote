package com.vaonis.vesperahelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One USB / SCSI partition reported by {@code vespera-netd.sh list-disks}. */
public final class UsbDisk {
    public final String name;
    public final String uuid;
    public final String label;
    public final String size;
    public final String fstype;
    public final String fsType;
    public final boolean mounted;
    public final String mountPoint;

    public UsbDisk(String name, String uuid, String label, String size, String fstype,
                   boolean mounted, String mountPoint) {
        this.name = name == null ? "" : name;
        this.uuid = dash(uuid);
        this.label = dash(label);
        this.size = dash(size);
        this.fstype = dash(fstype);
        this.fsType = this.fstype;
        this.mounted = mounted;
        this.mountPoint = dash(mountPoint);
    }

    public String displayName() {
        if (!"-".equals(label) && !label.isEmpty()) return label;
        return name.isEmpty() ? "USB" : name;
    }

    public String displayTitle() {
        return displayName();
    }

    /** Prefer UUID so the same HD remounts after reboot even if the kernel name changes. */
    public String spec() {
        if (!"-".equals(uuid) && uuid.length() >= 4) return uuid;
        return name;
    }

    public String id() {
        return spec();
    }

    public String encode() {
        return name + "|" + uuid + "|" + label + "|" + size + "|" + fstype
                + "|" + (mounted ? "yes" : "no") + "|" + mountPoint;
    }

    public static UsbDisk parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] p = line.split("\\|", -1);
        if (p.length < 6) return null;
        if ("ok".equals(p[0]) || "none".equals(p[0]) || "ERR".equals(p[0])) return null;
        boolean mounted = p.length > 5 && "yes".equalsIgnoreCase(p[5]);
        String mp = p.length > 6 ? p[6] : "-";
        return new UsbDisk(p[0], p[1], p[2], p[3], p[4], mounted, mp);
    }

    public static List<UsbDisk> parseList(String ack) {
        List<UsbDisk> out = new ArrayList<>();
        if (ack == null) return out;
        for (String line : ack.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || "ok".equals(trimmed) || "none".equals(trimmed)) continue;
            UsbDisk disk = parse(trimmed);
            if (disk != null && !disk.name.isEmpty()) out.add(disk);
        }
        return out;
    }

    public boolean matches(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        String s = spec.trim().toLowerCase(Locale.US);
        return s.equals(name.toLowerCase(Locale.US))
                || s.equals(uuid.toLowerCase(Locale.US))
                || name.toLowerCase(Locale.US).endsWith("/" + s);
    }

    private static String dash(String value) {
        if (value == null || value.trim().isEmpty()) return "-";
        return value.trim();
    }
}
