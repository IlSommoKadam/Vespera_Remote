package com.vaonis.vesperahelper;

import android.app.AlarmManager;
import android.content.Context;
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
        boolean due = forceNtp
                || (!store.clockSyncedRecently(INTERVAL_MS) && store.clockSyncDue(now, INTERVAL_MS));
        if (due) {
            ntpAttempted = true;
            try {
                ntpMs = NtpClient.unixTimeMs();
                ntpOk = true;
                applySystemClock(context, zoneId, ntpMs);
                store.recordClockSync(ntpMs, true);
                SystemActivityLog.dropFuture(context);
                Log.i(TAG, "ntp ok tz=" + zoneId + " ms=" + ntpMs);
            } catch (Exception failure) {
                Log.w(TAG, "ntp failed: " + failure.getMessage());
                store.recordClockSync(now, false);
            }
        }
        boolean hoursChanged = store.applySunHours(ntpOk ? ntpMs : System.currentTimeMillis(), ntpOk);
        return new Result(ntpOk, hoursChanged, ntpAttempted, zoneId, ntpMs);
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
