package com.vaonis.vesperahelper;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Civil sunrise/sunset (sun upper limb, refraction −0.833°) from lat/lon.
 * Almanac for Computers, 1990 — hour precision is enough for sync windows.
 */
final class SunTimes {
    static final class Result {
        final boolean polar;
        final long sunriseMs;
        final long sunsetMs;

        Result(boolean polar, long sunriseMs, long sunsetMs) {
            this.polar = polar;
            this.sunriseMs = sunriseMs;
            this.sunsetMs = sunsetMs;
        }
    }

    private SunTimes() {}

    static Result compute(double latDeg, double lonDeg, long nowMs) {
        return compute(latDeg, lonDeg, nowMs, TimeZone.getDefault());
    }

    static Result compute(double latDeg, double lonDeg, long nowMs, TimeZone tz) {
        if (tz == null) tz = TimeZone.getDefault();
        Calendar local = Calendar.getInstance(tz);
        local.setTimeInMillis(nowMs);
        int year = local.get(Calendar.YEAR);
        int month = local.get(Calendar.MONTH) + 1;
        int day = local.get(Calendar.DAY_OF_MONTH);
        int n = local.get(Calendar.DAY_OF_YEAR);
        Long rise = eventUtcMs(year, month, day, n, latDeg, lonDeg, true);
        Long set = eventUtcMs(year, month, day, n, latDeg, lonDeg, false);
        if (rise == null || set == null) return new Result(true, 0, 0);
        return new Result(false, rise, set);
    }

    static int roundHour(long epochMs) {
        return roundHour(epochMs, TimeZone.getDefault());
    }

    static int roundHour(long epochMs, TimeZone tz) {
        Calendar calendar = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        calendar.setTimeInMillis(epochMs);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (calendar.get(Calendar.MINUTE) >= 30) hour = (hour + 1) % 24;
        return hour;
    }

    private static Long eventUtcMs(int year, int month, int day, int n,
                                   double lat, double lon, boolean sunrise) {
        double zenith = 90.833;
        double d2r = Math.PI / 180.0;
        double r2d = 180.0 / Math.PI;
        double lngHour = lon / 15.0;
        double t = sunrise
                ? n + ((6 - lngHour) / 24.0)
                : n + ((18 - lngHour) / 24.0);
        double M = (0.9856 * t) - 3.289;
        double L = M + (1.916 * Math.sin(M * d2r)) + (0.020 * Math.sin(2 * M * d2r)) + 282.634;
        L = norm360(L);
        double ra = r2d * Math.atan(0.91764 * Math.tan(L * d2r));
        ra = norm360(ra);
        double lQuad = Math.floor(L / 90.0) * 90.0;
        double raQuad = Math.floor(ra / 90.0) * 90.0;
        ra = (ra + (lQuad - raQuad)) / 15.0;
        double sinDec = 0.39782 * Math.sin(L * d2r);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(zenith * d2r) - (sinDec * Math.sin(lat * d2r)))
                / (cosDec * Math.cos(lat * d2r));
        if (cosH > 1 || cosH < -1) return null;
        double H = sunrise
                ? 360.0 - r2d * Math.acos(cosH)
                : r2d * Math.acos(cosH);
        H /= 15.0;
        double T = H + ra - (0.06571 * t) - 6.622;
        double ut = norm24(T - lngHour);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(year, month - 1, day, 0, 0, 0);
        utc.set(Calendar.MILLISECOND, 0);
        return utc.getTimeInMillis() + Math.round(ut * 3_600_000d);
    }

    private static double norm360(double value) {
        double v = value % 360.0;
        return v < 0 ? v + 360.0 : v;
    }

    private static double norm24(double value) {
        double v = value % 24.0;
        return v < 0 ? v + 24.0 : v;
    }
}
