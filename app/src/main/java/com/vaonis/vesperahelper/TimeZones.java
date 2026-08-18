package com.vaonis.vesperahelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;

/** IANA timezone from country or lat/lon. Used for sunrise hours and NTP clock. */
final class TimeZones {
    private TimeZones() {}

    static String resolve(double lat, double lon, String countryCode) {
        String fromCountry = fromCountry(countryCode);
        if (fromCountry != null) return fromCountry;
        String fromApi = fromApi(lat, lon);
        if (fromApi != null) return fromApi;
        return fallbackOffset(lon);
    }

    static TimeZone zone(String id) {
        if (id == null || id.isEmpty()) return TimeZone.getDefault();
        TimeZone zone = TimeZone.getTimeZone(id);
        if ("GMT".equals(zone.getID()) && id != null && !id.startsWith("GMT") && !"UTC".equalsIgnoreCase(id)) {
            return TimeZone.getDefault();
        }
        return zone;
    }

    private static String fromCountry(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) return null;
        switch (countryCode.trim().toUpperCase(Locale.US)) {
            case "IT":
            case "SM":
            case "VA":
                return "Europe/Rome";
            case "FR":
            case "MC":
                return "Europe/Paris";
            case "ES":
            case "AD":
                return "Europe/Madrid";
            case "PT":
                return "Europe/Lisbon";
            case "DE":
                return "Europe/Berlin";
            case "CH":
            case "LI":
                return "Europe/Zurich";
            case "AT":
                return "Europe/Vienna";
            case "BE":
            case "NL":
            case "LU":
                return "Europe/Brussels";
            case "GB":
            case "UK":
            case "IE":
                return "Europe/London";
            case "PL":
                return "Europe/Warsaw";
            case "GR":
                return "Europe/Athens";
            case "HR":
            case "SI":
            case "BA":
            case "ME":
            case "MK":
            case "RS":
                return "Europe/Belgrade";
            default:
                return null;
        }
    }

    private static String fromApi(double lat, double lon) {
        String[] urls = {
                "https://timeapi.io/api/timezone/coordinate?latitude=" + lat + "&longitude=" + lon,
                "https://timeapi.io/api/Time/current/coordinate?latitude=" + lat + "&longitude=" + lon
        };
        for (String url : urls) {
            try {
                JSONObject json = new JSONObject(httpGet(url));
                String zone = json.optString("timeZone", json.optString("timezone", "")).trim();
                if (!zone.isEmpty() && zone.contains("/")) return zone;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String fallbackOffset(double lon) {
        int hours = (int) Math.round(lon / 15.0);
        if (hours < -12) hours = -12;
        if (hours > 14) hours = 14;
        return String.format(Locale.US, "GMT%+d", hours);
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6_000);
        conn.setReadTimeout(6_000);
        conn.setRequestProperty("User-Agent", "VesperaHelper/0.6.27 (city timezone)");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        conn.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return body;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        stream.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
