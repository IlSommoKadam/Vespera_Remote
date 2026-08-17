package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the USB disk chosen after the first successful mount. */
final class UsbDiskStore {
    private static final String PREFS = "vespera_usb_disk";
    private static final String KEY_ID = "disk_id";
    private static final String KEY_NAME = "disk_name";
    private static final String KEY_LABEL = "disk_label";
    private static final String KEY_UUID = "disk_uuid";

    private final SharedPreferences prefs;

    private UsbDiskStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static UsbDiskStore from(Context context) {
        return new UsbDiskStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    boolean hasSaved() {
        return !getId().isEmpty();
    }

    String getId() { return prefs.getString(KEY_ID, ""); }
    String getName() { return prefs.getString(KEY_NAME, ""); }
    String getLabel() { return prefs.getString(KEY_LABEL, ""); }
    String getUuid() { return prefs.getString(KEY_UUID, ""); }

    void save(UsbDisk disk) {
        if (disk == null) return;
        prefs.edit()
                .putString(KEY_ID, disk.id())
                .putString(KEY_NAME, disk.name)
                .putString(KEY_LABEL, disk.label)
                .putString(KEY_UUID, disk.uuid)
                .apply();
    }

    void saveId(String id, String name, String label, String uuid) {
        prefs.edit()
                .putString(KEY_ID, id == null ? "" : id)
                .putString(KEY_NAME, name == null ? "" : name)
                .putString(KEY_LABEL, label == null ? "" : label)
                .putString(KEY_UUID, uuid == null ? "" : uuid)
                .apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }
}
