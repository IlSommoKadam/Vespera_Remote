package com.vaonis.vesperahelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Nominatim (OpenStreetMap) city search over Ethernet/Internet. */
final class CityGeocoder {
    static final class Hit {
        final String label;
        final double lat;
        final double lon;
        final String countryCode;

        Hit(String label, double lat, double lon, String countryCode) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.countryCode = countryCode == null ? "" : countryCode;
        }
    }

    private CityGeocoder() {}

    static List<Hit> search(String query, String language) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String lang = language == null || language.isEmpty() ? "it" : language;
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6"
                + "&addressdetails=1&accept-language=" + URLEncoder.encode(lang, "UTF-8")
                + "&q=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setRequestProperty("User-Agent", "VesperaHelper/0.6.22 (photo-sync night window)");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        conn.disconnect();
        if (code >= 400) {
            throw new Exception("HTTP " + code);
        }
        JSONArray array = new JSONArray(body);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row == null) continue;
            double lat = parseCoord(row.optString("lat", ""));
            double lon = parseCoord(row.optString("lon", ""));
            if (!valid(lat, lon)) continue;
            String name = row.optString("display_name", "").trim();
            if (name.isEmpty()) {
                name = String.format(Locale.US, "%.3f, %.3f", lat, lon);
            } else {
                int comma = name.indexOf(',');
                if (comma > 0 && comma < 48) {
                    String rest = name.substring(comma + 1).trim();
                    int second = rest.indexOf(',');
                    if (second > 0) rest = rest.substring(0, second).trim();
                    name = name.substring(0, comma).trim() + ", " + rest;
                }
            }
            hits.add(new Hit(name, lat, lon, countryCode(row)));
        }
        return hits;
    }

    private static String countryCode(JSONObject row) {
        JSONObject address = row.optJSONObject("address");
        if (address == null) return "";
        return address.optString("country_code", "").trim().toUpperCase(Locale.US);
    }

    private static boolean valid(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double parseCoord(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return Double.NaN;
        }
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
