package com.vaonis.vesperahelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Tab Sistema: elenco delle attività automatiche, con check e un unico Salva. */
final class SystemPanel {
    private final Activity activity;
    private final float density;
    private final SystemSettingsStore settings;
    private final PhotoSyncStore syncStore;
    private final UsbHdStore hdStore;
    private final FixedScrollView scroll;
    private final Row photoSync;
    private final Row storageSync;
    private final Row resumeSync;
    private final Row sunTooHigh;
    private final Row hdMount;
    private final Row clockNtp;
    private final Row bootStart;
    private final Row wifiConnect;
    private final Row singularityStart;
    private final Row watchdog;
    private final Row ftpLocal;
    private final Row keepAlive;
    private final TextView saveResult;
    private final TextView logBody;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean visible;
    private boolean logReceiverRegistered;
    private static final long LOG_REFRESH_MS = 2_000L;

    SystemPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.settings = SystemSettingsStore.from(activity);
        this.syncStore = PhotoSyncStore.from(activity);
        this.hdStore = UsbHdStore.from(activity);

        scroll = new FixedScrollView(activity);
        scroll.setVisibility(View.GONE);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        layout.addView(title(activity.getString(R.string.system_tab_title)));
        layout.addView(body(activity.getString(R.string.system_tab_intro)));
        logBody = addLogBox(layout);

        SystemSettingsStore.Snapshot snap = settings.snapshot();
        photoSync = addRow(layout, activity.getString(R.string.system_photo_sync_title), snap.photoSync);
        storageSync = addRow(layout, activity.getString(R.string.system_storage_sync_title), snap.storageSync);
        resumeSync = addRow(layout, activity.getString(R.string.system_resume_sync_title), snap.resumeSync);
        sunTooHigh = addRow(layout, activity.getString(R.string.system_sun_title), snap.sunTooHigh);
        hdMount = addRow(layout, activity.getString(R.string.system_hd_mount_title), snap.hdMount);
        clockNtp = addRow(layout, activity.getString(R.string.system_clock_title), snap.clockNtp);
        bootStart = addRow(layout, activity.getString(R.string.system_boot_title), snap.bootStart);
        wifiConnect = addRow(layout, activity.getString(R.string.system_wifi_title), snap.wifiConnect);
        singularityStart = addRow(layout,
                activity.getString(R.string.system_singularity_title), snap.singularityStart);
        watchdog = addRow(layout, activity.getString(R.string.system_watchdog_title), snap.watchdog);
        ftpLocal = addRow(layout, activity.getString(R.string.system_ftp_title), snap.ftpLocal);
        keepAlive = addRow(layout, activity.getString(R.string.system_keepalive_title), snap.keepAlive);

        Button save = new Button(activity);
        save.setAllCaps(true);
        save.setText(R.string.system_save);
        UiStyle.applyRaised(save, UiStyle.SLATE, true);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = (int) (8 * density);
        saveLp.bottomMargin = (int) (6 * density);
        save.setLayoutParams(saveLp);
        save.setOnClickListener(v -> saveAll());
        layout.addView(save);

        saveResult = body("");
        saveResult.setTextColor(UiStyle.GREEN);
        layout.addView(saveResult);

        scroll.addView(layout);
        refreshInfo();
        refreshLog();
    }

    View view() {
        return scroll;
    }

    void onVisible() {
        visible = true;
        loadChecks();
        refreshInfo();
        refreshLog();
        saveResult.setText("");
        if (!logReceiverRegistered) {
            IntentFilter filter = new IntentFilter(SystemActivityLog.ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(logReceiver, filter);
            }
            logReceiverRegistered = true;
        }
        mainHandler.removeCallbacks(logTick);
        mainHandler.postDelayed(logTick, LOG_REFRESH_MS);
    }

    void onHidden() {
        visible = false;
        mainHandler.removeCallbacks(logTick);
        if (logReceiverRegistered) {
            try {
                activity.unregisterReceiver(logReceiver);
            } catch (Exception ignored) {
            }
            logReceiverRegistered = false;
        }
    }

    boolean isTabActive() {
        return visible;
    }

    private void loadChecks() {
        SystemSettingsStore.Snapshot snap = settings.snapshot();
        photoSync.check.setChecked(snap.photoSync);
        storageSync.check.setChecked(snap.storageSync);
        resumeSync.check.setChecked(snap.resumeSync);
        sunTooHigh.check.setChecked(snap.sunTooHigh);
        hdMount.check.setChecked(snap.hdMount);
        clockNtp.check.setChecked(snap.clockNtp);
        bootStart.check.setChecked(snap.bootStart);
        wifiConnect.check.setChecked(snap.wifiConnect);
        singularityStart.check.setChecked(snap.singularityStart);
        watchdog.check.setChecked(snap.watchdog);
        ftpLocal.check.setChecked(snap.ftpLocal);
        keepAlive.check.setChecked(snap.keepAlive);
    }

    private void saveAll() {
        scroll.pin();
        SystemSettingsStore.Snapshot snap = new SystemSettingsStore.Snapshot();
        snap.photoSync = photoSync.check.isChecked();
        snap.storageSync = storageSync.check.isChecked();
        snap.resumeSync = resumeSync.check.isChecked();
        snap.sunTooHigh = sunTooHigh.check.isChecked();
        snap.hdMount = hdMount.check.isChecked();
        snap.clockNtp = clockNtp.check.isChecked();
        snap.bootStart = bootStart.check.isChecked();
        snap.wifiConnect = wifiConnect.check.isChecked();
        snap.singularityStart = singularityStart.check.isChecked();
        snap.watchdog = watchdog.check.isChecked();
        snap.ftpLocal = ftpLocal.check.isChecked();
        snap.keepAlive = keepAlive.check.isChecked();
        settings.save(snap);
        PhotoSyncService.applySettings(activity);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).onSystemSettingsSaved();
        }
        refreshInfo();
        refreshLog();
        saveResult.setText(R.string.system_saved);
    }

    private void refreshInfo() {
        SystemSettingsStore.Snapshot snap = settings.snapshot();
        photoSync.info.setText(photoSyncInfo(snap.photoSync));
        storageSync.info.setText(storageSyncInfo(snap.storageSync));
        resumeSync.info.setText(resumeSyncInfo(snap.resumeSync));
        sunTooHigh.info.setText(sunTooHighInfo(snap.sunTooHigh));
        hdMount.info.setText(hdMountInfo(snap.hdMount));
        clockNtp.info.setText(clockInfo(snap.clockNtp));
        bootStart.info.setText(prefixed(snap.bootStart,
                activity.getString(R.string.system_boot_info)));
        wifiConnect.info.setText(wifiInfo(snap.wifiConnect));
        singularityStart.info.setText(prefixed(snap.singularityStart,
                activity.getString(R.string.system_singularity_info)));
        watchdog.info.setText(watchdogInfo(snap.watchdog));
        ftpLocal.info.setText(ftpInfo(snap.ftpLocal));
        keepAlive.info.setText(prefixed(snap.keepAlive,
                activity.getString(R.string.system_keepalive_info)));
    }

    private void refreshLog() {
        List<SystemActivityLog.Entry> entries = SystemActivityLog.latest(activity);
        if (entries.isEmpty()) {
            logBody.setText(R.string.system_log_empty);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) text.append('\n');
            SystemActivityLog.Entry entry = entries.get(i);
            text.append(activity.getString(R.string.system_log_line,
                    formatDateTime(entry.at),
                    logKindLabel(entry.kind),
                    logDetailLabel(entry.kind, entry.detail)));
        }
        String next = text.toString();
        if (!next.contentEquals(logBody.getText())) logBody.setText(next);
    }

    private String logKindLabel(String kind) {
        if (SystemActivityLog.KIND_PHOTO_SYNC.equals(kind)) {
            return activity.getString(R.string.system_photo_sync_title);
        }
        if (SystemActivityLog.KIND_STORAGE_SYNC.equals(kind)) {
            return activity.getString(R.string.system_storage_sync_title);
        }
        if (SystemActivityLog.KIND_RESUME_SYNC.equals(kind)) {
            return activity.getString(R.string.system_resume_sync_title);
        }
        if (SystemActivityLog.KIND_SUN_TOO_HIGH.equals(kind)) {
            return activity.getString(R.string.system_sun_title);
        }
        if (SystemActivityLog.KIND_HD_MOUNT.equals(kind)) {
            return activity.getString(R.string.system_hd_mount_title);
        }
        if (SystemActivityLog.KIND_CLOCK_NTP.equals(kind)) {
            return activity.getString(R.string.system_clock_title);
        }
        if (SystemActivityLog.KIND_BOOT.equals(kind)) {
            return activity.getString(R.string.system_boot_title);
        }
        if (SystemActivityLog.KIND_WIFI.equals(kind)) {
            return activity.getString(R.string.system_wifi_title);
        }
        if (SystemActivityLog.KIND_SINGULARITY.equals(kind)) {
            return activity.getString(R.string.system_singularity_title);
        }
        if (SystemActivityLog.KIND_FTP.equals(kind)) {
            return activity.getString(R.string.system_ftp_title);
        }
        if (SystemActivityLog.KIND_KEEP_ALIVE.equals(kind)) {
            return activity.getString(R.string.system_keepalive_title);
        }
        return kind == null ? "—" : kind;
    }

    private String logDetailLabel(String kind, String detail) {
        if (SystemActivityLog.DETAIL_OK.equals(detail)) {
            return activity.getString(R.string.system_log_ok);
        }
        if (SystemActivityLog.DETAIL_FAIL.equals(detail)) {
            return activity.getString(R.string.system_log_fail);
        }
        if (SystemActivityLog.DETAIL_PAUSED.equals(detail)) {
            return activity.getString(R.string.system_log_paused);
        }
        if (SystemActivityLog.KIND_SUN_TOO_HIGH.equals(kind)) {
            return sunTooHighResultLabel(detail);
        }
        return detail == null || detail.isEmpty() ? "—" : detail;
    }

    private String photoSyncInfo(boolean enabled) {
        String schedule = activity.getString(R.string.system_photo_sync_info,
                PhotoSyncStore.formatIntervalHours(syncStore.dayIntervalHours()),
                syncStore.dayStartHour(),
                syncStore.dayEndHour(),
                PhotoSyncStore.formatIntervalHours(syncStore.nightIntervalHours()),
                formatClock(syncStore.nextAutoAt(System.currentTimeMillis())));
        String last;
        if (syncStore.lastAt() <= 0) {
            last = activity.getString(R.string.system_photo_sync_never);
        } else {
            String when = formatDateTime(syncStore.lastAt());
            String detail = syncStore.lastOk()
                    ? activity.getString(R.string.photos_sync_done,
                    syncStore.lastCopied(), syncStore.lastSkipped(),
                    syncStore.lastDeleted(), 0,
                    PhotoSyncEngine.formatBytes(syncStore.lastBytes()))
                    : syncStore.lastError();
            if (detail == null || detail.isEmpty()) detail = "—";
            last = activity.getString(R.string.system_photo_sync_last, when, detail);
        }
        if (syncStore.paused()) {
            last = activity.getString(R.string.system_photo_sync_paused) + "\n" + last;
        }
        return prefixed(enabled, schedule + "\n" + last);
    }

    private String storageSyncInfo(boolean enabled) {
        return prefixed(enabled, activity.getString(R.string.system_storage_sync_info,
                PhotoSyncService.STORAGE_SYNC_PERCENT));
    }

    private String resumeSyncInfo(boolean enabled) {
        String body = activity.getString(R.string.system_resume_sync_info);
        String state = syncStore.hasSuspendedWork(activity)
                ? activity.getString(R.string.system_resume_sync_pending)
                : activity.getString(R.string.system_resume_sync_idle);
        return prefixed(enabled, body + "\n" + state);
    }

    private String sunTooHighInfo(boolean enabled) {
        String body = activity.getString(R.string.system_sun_info);
        if (!syncStore.hasSite()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_sun_unset));
        }
        long now = System.currentTimeMillis();
        long nextAt = syncStore.nextSunTooHighCheckAt(settings.sunTooHighDay(), now);
        String next;
        if (nextAt <= 0) {
            next = activity.getString(R.string.system_sun_unset);
        } else if (nextAt <= now + 5_000L) {
            next = activity.getString(R.string.system_sun_due);
        } else {
            next = activity.getString(R.string.system_sun_next, formatClock(nextAt));
        }
        String last;
        if (settings.sunTooHighAt() <= 0) {
            last = activity.getString(R.string.system_sun_never);
        } else {
            last = activity.getString(R.string.system_sun_last,
                    formatDateTime(settings.sunTooHighAt()),
                    sunTooHighResultLabel(settings.sunTooHighResult()));
        }
        return prefixed(enabled, body + "\n" + next + "\n" + last);
    }

    private String sunTooHighResultLabel(String code) {
        if (SystemSettingsStore.SUN_RESULT_SHUTDOWN_OK.equals(code)) {
            return activity.getString(R.string.system_sun_result_shutdown_ok);
        }
        if (SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL.equals(code)) {
            return activity.getString(R.string.system_sun_result_shutdown_fail);
        }
        if (SystemSettingsStore.SUN_RESULT_TRIGGERED.equals(code)) {
            return activity.getString(R.string.system_sun_result_triggered);
        }
        if (SystemSettingsStore.SUN_RESULT_NOT_STATUS.equals(code)) {
            return activity.getString(R.string.system_sun_result_not_status);
        }
        return code == null || code.isEmpty() ? "—" : code;
    }

    private String hdMountInfo(boolean enabled) {
        String body = activity.getString(R.string.system_hd_mount_info);
        String extra;
        if (!hdStore.isConfigured()) {
            extra = activity.getString(R.string.system_hd_mount_none);
        } else {
            extra = activity.getString(R.string.system_hd_mount_saved, hdStore.displayName());
        }
        return prefixed(enabled, body + "\n" + extra);
    }

    private String clockInfo(boolean enabled) {
        String body = activity.getString(R.string.system_clock_info);
        if (!syncStore.hasSite()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_clock_unset));
        }
        String tz = syncStore.siteTimeZone();
        if (tz == null || tz.isEmpty()) tz = TimeZone.getDefault().getID();
        long lastNtp = syncStore.lastNtpAt();
        String last = lastNtp > 0
                ? formatDateTime(lastNtp)
                : activity.getString(R.string.system_clock_never);
        String ok = activity.getString(syncStore.lastNtpOk()
                ? R.string.system_clock_ok_short
                : R.string.system_clock_fail_short);
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_clock_last, tz, last, ok));
    }

    private String wifiInfo(boolean enabled) {
        String body = activity.getString(R.string.system_wifi_info);
        VesperaDeviceStore device = VesperaDeviceStore.from(activity);
        if (!device.isConfigured()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_wifi_none));
        }
        String status = StatusTexts.connection(activity, VesperaConnectionService.getLastStatus());
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_wifi_device, device.getSsid(), status));
    }

    private String watchdogInfo(boolean enabled) {
        String body = activity.getString(R.string.system_watchdog_info);
        InstrumentWatchdog.Snapshot snap = InstrumentWatchdog.lastSnapshot();
        String status = snap == null
                ? activity.getString(R.string.system_watchdog_idle)
                : (snap.message == null || snap.message.isEmpty()
                ? StatusTexts.singularity(activity, snap.status)
                : snap.message);
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_watchdog_status, status));
    }

    private String ftpInfo(boolean enabled) {
        return prefixed(enabled, activity.getString(R.string.system_ftp_info));
    }

    private String prefixed(boolean enabled, String info) {
        if (enabled) return info;
        return activity.getString(R.string.system_activity_off) + "\n" + info;
    }

    private String formatClock(long timeMs) {
        Calendar calendar = Calendar.getInstance(syncStore.zone());
        calendar.setTimeInMillis(timeMs);
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private String formatDateTime(long timeMs) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        format.setTimeZone(syncStore.zone());
        return format.format(new Date(timeMs));
    }

    private Row addRow(LinearLayout layout, String title, boolean checked) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * density);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0xFFE8EEF4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        card.setLayoutParams(lp);

        CheckBox check = new CheckBox(activity);
        check.setText(title);
        check.setChecked(checked);
        check.setFocusable(false);
        check.setFocusableInTouchMode(false);
        check.setTextSize(15);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(0xFF1A237E);
        check.setPadding(0, 0, 0, (int) (2 * density));

        TextView info = new TextView(activity);
        info.setTextSize(13);
        info.setTextColor(0xFF455A64);
        info.setPadding((int) (8 * density), 0, 0, 0);

        card.addView(check);
        card.addView(info);
        layout.addView(card);
        return new Row(check, info);
    }

    private TextView addLogBox(LinearLayout layout) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * density);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0xFFDCEBFA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        lp.bottomMargin = (int) (4 * density);
        card.setLayoutParams(lp);

        TextView heading = new TextView(activity);
        heading.setText(R.string.system_log_title);
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
        heading.setTextColor(0xFF1A237E);
        heading.setTextSize(15);
        heading.setPadding(0, 0, 0, (int) (6 * density));

        TextView body = new TextView(activity);
        body.setTextSize(13);
        body.setTextColor(0xFF263238);
        body.setLineSpacing(0, 1.15f);

        card.addView(heading);
        card.addView(body);
        layout.addView(card);
        return body;
    }

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshInfo();
            refreshLog();
            mainHandler.postDelayed(this, LOG_REFRESH_MS);
        }
    };

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!visible) return;
            activity.runOnUiThread(() -> {
                if (!visible) return;
                refreshInfo();
                refreshLog();
            });
        }
    };

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, (int) (10 * density), 0, (int) (4 * density));
        view.setTextColor(0xFF1A237E);
        view.setTextSize(16);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setPadding(0, (int) (2 * density), 0, (int) (6 * density));
        return view;
    }

    private static final class Row {
        final CheckBox check;
        final TextView info;

        Row(CheckBox check, TextView info) {
            this.check = check;
            this.info = info;
        }
    }
}
