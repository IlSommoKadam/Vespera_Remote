package com.vaonis.vesperawifihelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;

import java.util.Locale;

/** Persists the selected Vespera AP so any I / II / Pro unit can be used. */
public final class VesperaDeviceStore {
    private static final String PREFS = "vespera_device";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_BSSID = "bssid";
    private static final String KEY_FREQUENCY = "frequency_mhz";
    private static final String KEY_MODEL = "model";

    private final SharedPreferences prefs;

    private VesperaDeviceStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static VesperaDeviceStore from(Context context) {
        return new VesperaDeviceStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public boolean isConfigured() {
        String ssid = getSsid();
        String bssid = getBssid();
        return ssid != null && !ssid.isEmpty() && bssid != null && !bssid.isEmpty();
    }

    public String getSsid() { return prefs.getString(KEY_SSID, ""); }
    public String getBssid() { return prefs.getString(KEY_BSSID, ""); }
    public int getFrequencyMhz() { return prefs.getInt(KEY_FREQUENCY, 0); }
    public String getModel() { return prefs.getString(KEY_MODEL, ""); }

    public void save(String ssid, String bssid, int frequencyMhz) {
        prefs.edit()
                .putString(KEY_SSID, ssid == null ? "" : ssid.trim())
                .putString(KEY_BSSID, bssid == null ? "" : bssid.trim().toLowerCase(Locale.US))
                .putInt(KEY_FREQUENCY, frequencyMhz)
                .putString(KEY_MODEL, guessModel(ssid))
                .apply();
    }

    public void saveFromScan(ScanResult result) {
        save(result.SSID, result.BSSID, result.frequency);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public String describe() {
        if (!isConfigured()) {
            return "Nessuno strumento salvato. Scansiona e seleziona il tuo Vespera (I / II / Pro).";
        }
        String model = getModel().isEmpty() ? "Vespera" : getModel();
        String freq = getFrequencyMhz() > 0 ? (getFrequencyMhz() + " MHz") : "canale auto";
        return model + "\nSSID: " + getSsid() + "\nBSSID: " + getBssid() + "\nCanale: " + freq;
    }

    /** Accepts official SSID forms: Vespera-*, Vespera 2-*, vespera2-*, VESPERAPRO-*. */
    public static boolean isVesperaSsid(String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) return false;
        String normalized = normalize(ssid);
        return normalized.contains("vespera");
    }

    public static String guessModel(String ssid) {
        String normalized = normalize(ssid);
        if (normalized.contains("vesperapro")) return "Vespera Pro";
        if (normalized.contains("vespera2")) return "Vespera II";
        if (normalized.contains("vespera")) return "Vespera";
        return "Vespera";
    }

    private static String normalize(String ssid) {
        return ssid.toLowerCase(Locale.US)
                .replace(" ", "")
                .replace("_", "")
                .replace("–", "-")
                .replace("—", "-");
    }
}
