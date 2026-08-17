package com.vaonis.vesperahelper;

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
                .putString(KEY_SSID, ssid == null ? "" : stripQuotes(ssid.trim()))
                .putString(KEY_BSSID, normalizeBssid(bssid))
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

    /** True if this scan hit is the currently saved instrument. */
    public boolean matchesScan(ScanResult result) {
        if (!isConfigured() || result == null) return false;
        return normalize(getSsid()).equals(normalize(result.SSID))
                && normalizeBssid(getBssid()).equals(normalizeBssid(result.BSSID));
    }

    public String describe(Context context) {
        if (!isConfigured()) {
            return context.getString(R.string.no_device_saved);
        }
        String model = getModel().isEmpty() ? "Vespera" : getModel();
        String freq = getFrequencyMhz() > 0
                ? context.getString(R.string.channel_mhz, getFrequencyMhz())
                : context.getString(R.string.channel_auto);
        return context.getString(R.string.device_detail, model, getSsid(), getBssid(), freq);
    }

    /** Accepts official SSID forms: Vespera-*, Vespera 2-*, vespera2-*, VESPERAPRO-*. */
    public static boolean isVesperaSsid(String ssid) {
        if (ssid == null || stripQuotes(ssid.trim()).isEmpty()) return false;
        return normalize(ssid).contains("vespera");
    }

    public static String guessModel(String ssid) {
        String normalized = normalize(ssid);
        if (normalized.contains("vesperapro")) return "Vespera Pro";
        if (normalized.contains("vespera2")) return "Vespera II";
        if (normalized.contains("vespera")) return "Vespera";
        return "Vespera";
    }

    private static String normalize(String ssid) {
        return stripQuotes(ssid == null ? "" : ssid)
                .toLowerCase(Locale.US)
                .replace(" ", "")
                .replace("_", "")
                .replace("–", "-")
                .replace("—", "-");
    }

    private static String normalizeBssid(String bssid) {
        return bssid == null ? "" : bssid.trim().toLowerCase(Locale.US);
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
