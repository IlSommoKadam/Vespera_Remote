package com.vaonis.vesperahelper;

import android.app.AlarmManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * City timezone + NTP. Sets the Pi clock through AlarmManager when allowed,
 * otherwise via the root daemon. Never uses the Vespera.
 */
final class SiteClock {
    private static final String TAG = "VesperaClock";
    static final long INTERVAL_MS = 18 * 60 * 60 * 1000L;
    static final long RETRY_MS = 2 * 60 * 1000L;
    static final long APPLY_SKEW_MS = 90 * 1000L;

    static final class Result {
        final boolean ntpOk;
        final boolean hoursChanged;
        final boolean ntpAttempted;
        final String timeZoneId;
        final long ntpMs;

        Result(boolean ntpOk, boolean hoursChanged, boolean ntpAttempted,
               String timeZoneId, long ntpMs) {
            this.ntpOk = ntpOk;
            this.hoursChanged = hoursChanged;
            this.ntpAttempted = ntpAttempted;
            this.timeZoneId = timeZoneId == null ? "" : timeZoneId;
            this.ntpMs = ntpMs;
        }
    }

    private SiteClock() {}

    static Result sync(Context context, PhotoSyncStore store, boolean forceNtp) {
        if (store == null || !store.hasSite()) {
            return new Result(false, false, false, "", 0);
        }
        String zoneId = store.siteTimeZone();
        if (zoneId.isEmpty()) {
            zoneId = TimeZones.resolve(store.siteLat(), store.siteLon(), store.siteCountry());
            store.setSiteTimeZone(zoneId);
        }
        long now = System.currentTimeMillis();
        boolean ntpOk = false;
        boolean ntpAttempted = false;
        long ntpMs = now;
        boolean skewed = store.clockLooksWrong(now);
        long minGap = (skewed || !store.lastNtpOk()) ? RETRY_MS : INTERVAL_MS;
        boolean due = forceNtp
                || (!store.clockSyncedRecently(minGap)
                && (skewed || store.clockSyncDue(now, INTERVAL_MS)));
        if (due) {
            ntpAttempted = true;
            try {
                ntpMs = NtpClient.unixTimeMs();
                applySystemClock(context, zoneId, ntpMs);
                ntpOk = clockMatches(ntpMs) || waitForClock(ntpMs, 2_000);
                store.recordClockSync(ntpOk ? ntpMs : System.currentTimeMillis(), ntpOk);
                if (ntpOk) SystemActivityLog.dropFuture(context);
                Log.i(TAG, (ntpOk ? "ntp ok" : "ntp set but wall clock still wrong")
                        + " tz=" + zoneId + " ntp=" + ntpMs
                        + " wall=" + System.currentTimeMillis());
            } catch (Exception failure) {
                Log.w(TAG, "ntp failed: " + failure.getMessage());
                store.recordClockSync(System.currentTimeMillis(), false);
            }
        }
        boolean hoursChanged = store.applySunHours(ntpOk ? ntpMs : System.currentTimeMillis(), ntpOk);
        return new Result(ntpOk, hoursChanged, ntpAttempted, zoneId, ntpMs);
    }

    private static boolean clockMatches(long ntpMs) {
        return Math.abs(System.currentTimeMillis() - ntpMs) < APPLY_SKEW_MS;
    }

    private static boolean waitForClock(long ntpMs, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (clockMatches(ntpMs)) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return clockMatches(ntpMs);
            }
        }
        return clockMatches(ntpMs);
    }

    private static void applySystemClock(Context context, String zoneId, long ntpMs) {
        if (zoneId != null && !zoneId.isEmpty()) {
            try {
                AlarmManager alarm = context.getSystemService(AlarmManager.class);
                if (alarm != null) alarm.setTimeZone(zoneId);
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        try {
            AlarmManager alarm = context.getSystemService(AlarmManager.class);
            if (alarm != null) alarm.setTime(ntpMs);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String stamp = fmt.format(new Date(ntpMs));
        String tz = zoneId == null ? "" : zoneId.replace('|', '/');
        VesperaConnectionService.writeNetRequest(context,
                "set-clock|" + tz + "|" + (ntpMs / 1000L) + "|" + stamp);
    }
}
