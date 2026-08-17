package com.vaonis.vesperahelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mounts the USB HD, syncs Vespera /USER photos by day, and serves them over FTP. */
public final class PhotoSyncService extends Service {
    public static final String ACTION_STATUS = "com.vaonis.vesperahelper.PHOTO_STATUS";
    public static final String ACTION_PROGRESS = "com.vaonis.vesperahelper.PHOTO_PROGRESS";
    public static final String ACTION_REFRESH_DISKS = "com.vaonis.vesperahelper.PHOTO_REFRESH";
    public static final String ACTION_LIST_DISKS = ACTION_REFRESH_DISKS;
    public static final String ACTION_MOUNT = "com.vaonis.vesperahelper.PHOTO_MOUNT";
    public static final String ACTION_UNMOUNT = "com.vaonis.vesperahelper.PHOTO_UNMOUNT";
    public static final String ACTION_EJECT = "com.vaonis.vesperahelper.PHOTO_EJECT";
    public static final String ACTION_SYNC_NOW = "com.vaonis.vesperahelper.PHOTO_SYNC_NOW";
    public static final String ACTION_FORGET = "com.vaonis.vesperahelper.PHOTO_FORGET";
    public static final String ACTION_BOOTSTRAP = "com.vaonis.vesperahelper.PHOTO_BOOTSTRAP";
    public static final String ACTION_RESUME = "com.vaonis.vesperahelper.PHOTO_RESUME";
    public static final String ACTION_PAUSE = "com.vaonis.vesperahelper.PHOTO_PAUSE";
    public static final String ACTION_HIDE_WINDOW = "com.vaonis.vesperahelper.PHOTO_HIDE_WINDOW";
    public static final String ACTION_SHOW_WINDOW = "com.vaonis.vesperahelper.PHOTO_SHOW_WINDOW";
    public static final String EXTRA_SPEC = "spec";
    public static final String EXTRA_DISK_ID = "disk_id";
    public static final String EXTRA_DISKS = "disks";
    public static final String EXTRA_HD = "hd";
    public static final String EXTRA_FTP = "ftp";
    public static final String EXTRA_SYNC = "sync";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_FTP_HINT = "ftp_hint";
    public static final String EXTRA_FILE_COUNT = "file_count";
    public static final String EXTRA_LAST_SYNC = "last_sync";
    public static final String EXTRA_MOUNTED = "mounted";
    public static final String EXTRA_FTP_RUNNING = "ftp_running";
    public static final String EXTRA_SELECTED = "selected";
    public static final String EXTRA_SYNCING = "syncing";
    public static final String EXTRA_PAUSED = "paused";

    public static final int DAY_START_HOUR = 7;
    public static final int DAY_END_HOUR = 19;
    private static final String TAG = "VesperaPhotos";
    private static final int NOTIFICATION_ID = 43;
    private static final long TICK_MS = 30_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Object syncLock = new Object();
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final SimpleFtpServer ftpServer = new SimpleFtpServer();
    private final FtpProxyServer telescopeFtp = new FtpProxyServer();
    private int vesperaFtpPort = -1;
    private SyncProgressHud hud;
    private UsbHdStore hdStore;
    private PhotoSyncStore syncStore;
    private boolean mounted;
    private String mountLabel = "";
    private String selectedSpec = "";
    private String[] disksEncoded = new String[0];
    private String hdStatus = "";
    private String ftpStatus = "";
    private String syncStatus = "";
    private String lastSync = "";
    private String message = "";
    private int fileCount;
    private boolean syncing;
    private boolean userUnmounted;
    private long lastNotifyAt;
    private SyncProgress lastProgress;
    private String lastBroadcastPhase = "";
    private boolean connectionReceiverRegistered;
    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(VesperaConnectionService.EXTRA_STATUS);
            if (VesperaConnectionService.STATUS_CONNECTED.equals(status)) {
                worker.execute(PhotoSyncService.this::refreshTelescopeFtpLocked);
                syncExecutor.execute(() -> maybeAutoSync(shouldResume()));
            } else {
                worker.execute(() -> {
                    telescopeFtp.stop();
                    refreshFtpStatus();
                    publish();
                });
            }
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            worker.execute(() -> {
                maybeAutoMount();
                refreshTelescopeFtpLocked();
                publish();
            });
            syncExecutor.execute(() -> maybeAutoSync(false));
            mainHandler.postDelayed(this, TICK_MS);
        }
    };

    public static void ensure(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_BOOTSTRAP));
    }

    public static void start(Context context) {
        ensure(context);
    }

    public static List<UsbDisk> parseDisks(String blob) {
        return UsbDisk.parseList(blob);
    }

    public static void refreshDisks(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_REFRESH_DISKS));
    }

    public static void mount(Context context, String spec) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_MOUNT).putExtra(EXTRA_SPEC, spec));
    }

    public static void unmount(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_UNMOUNT));
    }

    public static void eject(Context context, String spec) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_EJECT).putExtra(EXTRA_SPEC, spec));
    }

    public static void syncNow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SYNC_NOW));
    }

    public static void resumeSync(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_RESUME));
    }

    public static void pauseSync(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_PAUSE));
    }

    public static void hideWindow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_HIDE_WINDOW));
    }

    public static void showWindow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SHOW_WINDOW));
    }

    public static void forget(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_FORGET));
    }

    public static boolean isDaytime() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= PhotoSyncStore.DEFAULT_DAY_START && hour < PhotoSyncStore.DEFAULT_DAY_END;
    }

    private boolean isDaytimeLocal() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= syncStore.dayStartHour() && hour < syncStore.dayEndHour();
    }

    @Override public void onCreate() {
        super.onCreate();
        hdStore = UsbHdStore.from(this);
        syncStore = PhotoSyncStore.from(this);
        selectedSpec = hdStore.getSpec();
        if (selectedSpec.isEmpty()) selectedSpec = UsbDiskStore.from(this).getId();
        Context localized = AppLocale.wrap(this);
        NotificationChannel channel = new NotificationChannel(
                "vespera_photos",
                localized.getString(R.string.photos_notification_title),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        hdStatus = localized.getString(R.string.photos_hd_unmounted);
        ftpStatus = localized.getString(R.string.photos_ftp_off);
        syncStatus = localized.getString(R.string.photos_sync_idle);
        hud = new SyncProgressHud(this);
        startAsForeground();
        if (!connectionReceiverRegistered) {
            IntentFilter filter = new IntentFilter(VesperaConnectionService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(connectionReceiver, filter);
            }
            connectionReceiverRegistered = true;
        }
        worker.execute(() -> {
            refreshMountStatus();
            bootstrapMountLocked();
            if (mounted) startFtpLocked();
            refreshTelescopeFtpLocked();
            listDisksLocked();
            publish();
            syncExecutor.execute(() -> maybeAutoSync(shouldResume()));
        });
        mainHandler.postDelayed(tick, TICK_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BOOTSTRAP.equals(action) || action == null) {
            worker.execute(() -> {
                userUnmounted = false;
                refreshMountStatus();
                bootstrapMountLocked();
                if (mounted) startFtpLocked();
                listDisksLocked();
                publish();
            });
        } else if (ACTION_REFRESH_DISKS.equals(action) || ACTION_LIST_DISKS.equals(action)) {
            worker.execute(() -> {
                listDisksLocked();
                if (!mounted) bootstrapMountLocked();
                refreshMountStatus();
                publish();
            });
        } else if (ACTION_MOUNT.equals(action)) {
            String spec = intent.getStringExtra(EXTRA_SPEC);
            if (spec == null || spec.isEmpty()) spec = intent.getStringExtra(EXTRA_DISK_ID);
            final String mountSpec = spec;
            worker.execute(() -> mountLocked(mountSpec));
        } else if (ACTION_UNMOUNT.equals(action)) {
            worker.execute(this::unmountLocked);
        } else if (ACTION_EJECT.equals(action)) {
            String spec = intent.getStringExtra(EXTRA_SPEC);
            if (spec == null || spec.isEmpty()) spec = intent.getStringExtra(EXTRA_DISK_ID);
            final String ejectSpec = spec;
            worker.execute(() -> ejectLocked(ejectSpec));
        } else if (ACTION_SYNC_NOW.equals(action)) {
            pauseRequested.set(false);
            if (syncStore != null) syncStore.setPaused(false);
            syncExecutor.execute(() -> maybeAutoSync(true));
        } else if (ACTION_RESUME.equals(action)) {
            pauseRequested.set(false);
            if (syncStore != null) syncStore.setPaused(false);
            if (hud != null) hud.show();
            syncExecutor.execute(() -> maybeAutoSync(true));
        } else if (ACTION_PAUSE.equals(action)) {
            pauseRequested.set(true);
            if (syncStore != null) {
                syncStore.markInterrupted(this);
                syncStore.setPaused(true);
            }
            if (hud != null) hud.hideByUser();
            publish();
        } else if (ACTION_HIDE_WINDOW.equals(action)) {
            if (hud != null) hud.hideByUser();
        } else if (ACTION_SHOW_WINDOW.equals(action)) {
            if (hud != null) hud.show();
        } else if (ACTION_FORGET.equals(action)) {
            worker.execute(this::forgetLocked);
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification notification() {
        Context localized = AppLocale.wrap(this);
        String hd = hdStatus == null || hdStatus.isEmpty()
                ? localized.getString(R.string.photos_hd_unmounted) : hdStatus;
        String ftp = ftpServer.isRunning()
                ? localized.getString(R.string.photos_ftp_on, SimpleFtpServer.PORT)
                : localized.getString(R.string.photos_ftp_off);
        String sync = syncStatus == null || syncStatus.isEmpty()
                ? localized.getString(R.string.photos_sync_idle) : syncStatus;
        Intent popup = new Intent(this, SyncProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent content = PendingIntent.getActivity(this, 0, popup,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "vespera_photos")
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle(syncing
                        ? localized.getString(R.string.photo_sync_popup_title)
                        : localized.getString(R.string.photos_notification_title))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (syncing && lastProgress != null) {
            builder.setContentText(lastProgress.compactStatus())
                    .setProgress(1000, lastProgress.permille(), false)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            SyncProgress.phaseLabel(localized, lastProgress.phase)
                                    + "\n" + lastProgress.compactStatus()
                                    + "\n" + localized.getString(R.string.photo_sync_eta,
                                    SyncProgress.formatEta(lastProgress.etaMs))));
        } else {
            builder.setContentText(localized.getString(R.string.photos_notification_text, hd, ftp, sync))
                    .setProgress(0, 0, false);
        }
        return builder.build();
    }

    private void listDisksLocked() {
        Context localized = AppLocale.wrap(this);
        message = localized.getString(R.string.photos_scanning);
        publish();
        String raw = DaemonDisk.listRaw(this);
        List<UsbDisk> disks = UsbDisk.parseList(raw);
        List<String> encoded = new ArrayList<>();
        for (UsbDisk disk : disks) encoded.add(disk.encode());
        disksEncoded = encoded.toArray(new String[0]);
        if (raw == null) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (disks.isEmpty()) {
            message = localized.getString(R.string.photos_none);
        } else {
            message = localized.getString(R.string.photos_found, disks.size());
            if (selectedSpec.isEmpty() && hdStore.isConfigured()) {
                selectedSpec = hdStore.getSpec();
            }
        }
        refreshMountStatus();
        bindListedDiskIfNeeded();
    }

    private void mountLocked(String spec) {
        Context localized = AppLocale.wrap(this);
        String use = spec == null || spec.trim().isEmpty() ? selectedSpec : spec.trim();
        if (use.isEmpty()) use = hdStore.getSpec();
        if (use.isEmpty()) {
            message = localized.getString(R.string.photos_select_first);
            publish();
            return;
        }
        selectedSpec = use;
        userUnmounted = false;
        DaemonDisk.MountStatus status = DaemonDisk.mount(this, use);
        applyMountStatus(status);
        if (status.timeout) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (status.mounted) {
            hdStore.rememberMount(status.uuid, status.label, status.device);
            UsbDisk listed = findDisk(use);
            if (listed != null) {
                hdStore.saveMounted(listed);
                UsbDiskStore.from(this).save(listed);
            } else {
                UsbDiskStore.from(this).saveId(use, status.device, status.label, status.uuid);
            }
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
            startFtpLocked();
        } else if (status.raw != null && status.raw.startsWith("mount-unsupported-ntfs")) {
            message = localized.getString(R.string.photos_mount_ntfs);
            stopFtpLocked();
        } else {
            message = localized.getString(R.string.photos_mount_fail, status.raw);
            stopFtpLocked();
        }
        listDisksLocked();
        publish();
    }

    private void unmountLocked() {
        Context localized = AppLocale.wrap(this);
        stopFtpLocked();
        userUnmounted = true;
        DaemonDisk.MountStatus status = DaemonDisk.unmount(this, selectedSpec);
        applyMountStatus(status);
        if (status.timeout) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (!status.mounted) {
            message = localized.getString(R.string.photos_unmount_ok);
        } else {
            userUnmounted = false;
            message = localized.getString(R.string.photos_unmount_fail, status.raw);
        }
        listDisksLocked();
        publish();
    }

    private void ejectLocked(String spec) {
        Context localized = AppLocale.wrap(this);
        String use = spec == null || spec.trim().isEmpty() ? selectedSpec : spec.trim();
        if (use.isEmpty()) use = hdStore.getSpec();
        if (use.isEmpty()) use = UsbDiskStore.from(this).getId();
        if (use.isEmpty()) {
            message = localized.getString(R.string.photos_select_first);
            publish();
            return;
        }
        stopFtpLocked();
        userUnmounted = true;
        DaemonDisk.MountStatus status = DaemonDisk.eject(this, use);
        applyMountStatus(status);
        if (status.timeout) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (status.raw.startsWith("ejected") || !status.mounted) {
            message = localized.getString(R.string.photos_eject_ok);
        } else {
            userUnmounted = false;
            message = localized.getString(R.string.photos_eject_fail, status.raw);
        }
        listDisksLocked();
        publish();
    }

    private void forgetLocked() {
        Context localized = AppLocale.wrap(this);
        hdStore.clear();
        selectedSpec = "";
        message = localized.getString(R.string.photos_forget_ok);
        publish();
    }

    private void bootstrapMountLocked() {
        if (mounted) return;
        Context localized = AppLocale.wrap(this);
        message = localized.getString(R.string.photo_hd_auto_mount,
                hdStore.displayName().isEmpty() ? "HD" : hdStore.displayName());
        publish();
        maybeAutoMount();
        if (!mounted) {
            listDisksLocked();
            maybeAutoMount();
        }
    }

    private void maybeAutoMount() {
        if (userUnmounted) return;
        if (mounted) return;
        String spec = hdStore.getSpec();
        if (spec.isEmpty()) spec = UsbDiskStore.from(this).getId();
        if (spec.isEmpty() && disksEncoded != null && disksEncoded.length == 1) {
            UsbDisk only = UsbDisk.parse(disksEncoded[0]);
            if (only != null) spec = only.id();
        }
        if (spec.isEmpty()) return;
        selectedSpec = spec;
        DaemonDisk.MountStatus status = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            status = DaemonDisk.mount(this, spec);
            if (status != null && status.mounted) break;
            if (status != null && !status.timeout
                    && status.raw != null && status.raw.startsWith("mount-unsupported")) {
                break;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (status == null) return;
        applyMountStatus(status);
        if (status.mounted) {
            hdStore.rememberMount(status.uuid, status.label, status.device);
            UsbDisk listed = findDisk(spec);
            if (listed != null) {
                hdStore.saveMounted(listed);
                UsbDiskStore.from(this).save(listed);
            } else {
                UsbDiskStore.from(this).saveId(spec, status.device, status.label, status.uuid);
            }
            startFtpLocked();
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
        } else if (status.timeout) {
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_daemon_timeout);
        }
    }

    private void refreshMountStatus() {
        DaemonDisk.MountStatus status = DaemonDisk.status(this);
        applyMountStatus(status);
        if (mounted) startFtpLocked();
        else stopFtpLocked();
    }

    private void applyMountStatus(DaemonDisk.MountStatus status) {
        Context localized = AppLocale.wrap(this);
        mounted = status != null && status.mounted && !status.timeout;
        if (mounted) {
            mountLabel = !"-".equals(status.label) ? status.label
                    : (!"-".equals(status.uuid) ? status.uuid : status.device);
            hdStatus = localized.getString(R.string.photos_hd_mounted, mountLabel);
            fileCount = PhotoSyncEngine.countLocalPhotos(DaemonDisk.photosDir(this));
        } else if (hdStore.isAutoMount()) {
            hdStatus = localized.getString(R.string.photos_hd_waiting);
            fileCount = 0;
        } else {
            hdStatus = localized.getString(R.string.photos_hd_unmounted);
            fileCount = 0;
        }
        refreshSyncHint();
        refreshFtpStatus();
    }

    private void refreshSyncHint() {
        Context localized = AppLocale.wrap(this);
        if (syncing) {
            syncStatus = localized.getString(R.string.photos_sync_running);
            return;
        }
        if (syncStore != null && syncStore.paused()) {
            syncStatus = localized.getString(R.string.photo_sync_paused);
            return;
        }
        if (!mounted) {
            syncStatus = localized.getString(R.string.photos_sync_need_hd);
            return;
        }
        if (!isVesperaConnected()) {
            syncStatus = localized.getString(R.string.photos_sync_need_vespera);
            return;
        }
        if (!isDaytimeLocal()) {
            syncStatus = localized.getString(R.string.photos_sync_night, syncStore.dayStartHour());
            return;
        }
        long nextAt = syncStore.nextAutoAt(System.currentTimeMillis());
        syncStatus = localized.getString(R.string.photos_sync_next,
                formatClock(nextAt),
                PhotoSyncStore.formatIntervalHours(syncStore.intervalHours()));
    }

    private static String formatClock(long timeMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        return String.format(java.util.Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private void maybeAutoSync(boolean force) {
        Context localized = AppLocale.wrap(this);
        boolean resume = shouldResume();
        synchronized (syncLock) {
            if (syncing) {
                if ((force || resume) && hud != null) hud.show();
                return;
            }
        }
        if (syncStore != null && syncStore.paused() && !force) {
            refreshSyncHint();
            return;
        }
        if (!force && !resume && !syncStore.isAutoDue(System.currentTimeMillis())) {
            refreshSyncHint();
            publish();
            return;
        }
        ensureMountedForSync();
        File root = DaemonDisk.photosDir(this);
        if (!mounted || root == null || !root.isDirectory()) {
            message = localized.getString(R.string.photos_sync_need_hd);
            if (force) {
                showSyncGate(SyncProgress.PHASE_ERROR, message);
            }
            refreshSyncHint();
            publish();
            return;
        }
        if (!force && !resume && !isDaytimeLocal()) {
            refreshSyncHint();
            publish();
            return;
        }
        Network network = resolveVesperaNetwork();
        if (network == null && !isVesperaConnected() && !canReachVesperaFtp(null)) {
            message = localized.getString(R.string.photos_sync_need_vespera);
            if (force) {
                showSyncGate(SyncProgress.PHASE_ERROR, message);
            }
            refreshSyncHint();
            publish();
            return;
        }
        synchronized (syncLock) {
            if (syncing) return;
            syncing = true;
        }
        if (!force && !resume) syncStore.recordAttempt();
        syncStore.markInProgress(this);
        pauseRequested.set(false);
        lastProgress = new SyncProgress();
        lastProgress.active = true;
        lastProgress.phase = SyncProgress.PHASE_LIST;
        lastProgress.detail = resume
                ? localized.getString(R.string.photo_sync_resume)
                : localized.getString(R.string.photo_sync_listing);
        refreshSyncHint();
        if (hud != null) {
            hud.show();
            hud.bind(lastProgress);
        }
        publish();
        PhotoSyncEngine.Result result = PhotoSyncEngine.sync(network, root, this::onEngineProgress,
                pauseRequested::get);
        synchronized (syncLock) {
            syncing = false;
        }
        boolean paused = "paused".equals(result.error);
        boolean complete = result.error == null && result.failed == 0;
        boolean startedWork = result.downloaded > 0 || result.skipped > 0
                || result.deleted > 0 || PhotoSyncEngine.hasIncomplete(root);
        if (paused) {
            syncStore.markInterrupted(this);
            syncStore.setPaused(true);
            lastSync = localized.getString(R.string.photo_sync_paused);
        } else if (complete) {
            syncStore.clearInProgress(this);
        } else if (!startedWork) {
            syncStore.clearInProgress(this);
        } else {
            syncStore.markInterrupted(this);
        }
        if (paused) {
            lastSync = localized.getString(R.string.photo_sync_paused);
        } else if (result.error != null) {
            syncStore.recordFailure(result.error);
            if ("missing-user".equals(result.error)) {
                lastSync = localized.getString(R.string.photos_sync_no_user);
            } else if ("hd-unmounted".equals(result.error)) {
                lastSync = localized.getString(R.string.photos_sync_need_hd);
            } else {
                lastSync = localized.getString(R.string.photos_sync_error, result.error);
            }
        } else {
            syncStore.recordSuccess(result.downloaded, result.skipped, result.deleted, result.bytes);
            syncStore.setPhotosPath(new File(root, "USER").getAbsolutePath());
            lastSync = formatSyncSummary(localized, result, new File(root, "USER"));
        }
        message = lastSync;
        if (lastProgress != null) {
            lastProgress.active = false;
            lastProgress.phase = paused ? SyncProgress.PHASE_PAUSED
                    : (result.error != null ? SyncProgress.PHASE_ERROR : SyncProgress.PHASE_DONE);
            lastProgress.detail = lastSync;
            lastProgress.etaMs = 0;
            if (hud != null) hud.bind(lastProgress);
        }
        fileCount = PhotoSyncEngine.countLocalPhotos(root);
        refreshSyncHint();
        publish();
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName()));
        if (hud != null && paused) hud.hideByUser();
    }

    private void ensureMountedForSync() {
        if (userUnmounted) {
            refreshMountStatus();
            return;
        }
        refreshMountStatus();
        if (mounted) return;
        if (disksEncoded == null || disksEncoded.length == 0) {
            listDisksLocked();
        } else {
            bindListedDiskIfNeeded();
        }
        if (!mounted) maybeAutoMount();
    }

    private void bindListedDiskIfNeeded() {
        if (mounted || userUnmounted) return;
        UsbDisk chosen = null;
        for (String encoded : disksEncoded) {
            UsbDisk disk = UsbDisk.parse(encoded);
            if (disk == null || !disk.mounted) continue;
            if (chosen == null) chosen = disk;
            if (!selectedSpec.isEmpty() && disk.matches(selectedSpec)) {
                chosen = disk;
                break;
            }
            if (hdStore.isConfigured() && disk.matches(hdStore.getSpec())) {
                chosen = disk;
                break;
            }
        }
        if (chosen == null) return;
        selectedSpec = chosen.id();
        DaemonDisk.MountStatus status = DaemonDisk.mount(this, chosen.id());
        applyMountStatus(status);
        if (status != null && status.mounted) {
            hdStore.rememberMount(status.uuid, status.label, status.device);
            hdStore.saveMounted(chosen);
            UsbDiskStore.from(this).save(chosen);
            startFtpLocked();
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
        }
    }

    private boolean shouldResume() {
        File root = DaemonDisk.photosDir(this);
        return (syncStore != null && syncStore.shouldResume(this))
                || PhotoSyncEngine.hasIncomplete(root);
    }

    private String formatSyncSummary(Context localized, PhotoSyncEngine.Result result, File localUser) {
        StringBuilder text = new StringBuilder();
        boolean ok = result.failed == 0;
        text.append(ok
                ? localized.getString(R.string.photo_sync_summary_ok)
                : localized.getString(R.string.photo_sync_summary_partial, result.failed));
        text.append('\n');
        text.append(localized.getString(R.string.photo_sync_summary_path,
                result.remoteRoot, localUser == null ? "USER" : localUser.getAbsolutePath()));
        text.append('\n');
        text.append(localized.getString(R.string.photos_sync_done,
                result.downloaded, result.skipped, result.deleted, result.failed,
                PhotoSyncEngine.formatBytes(result.bytes)));
        if (result.folders != null && !result.folders.isEmpty()) {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_folders));
            for (String folder : result.folders) {
                text.append('\n').append("  ").append(folder);
            }
        }
        if (result.files != null && !result.files.isEmpty()) {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_files,
                    result.files.size()));
            int limit = Math.min(result.files.size(), 40);
            for (int i = 0; i < limit; i++) {
                PhotoSyncEngine.FileLine line = result.files.get(i);
                String kind = "copied".equals(line.kind)
                        ? localized.getString(R.string.photo_sync_kind_copied)
                        : ("skipped".equals(line.kind)
                        ? localized.getString(R.string.photo_sync_kind_skipped)
                        : localized.getString(R.string.photo_sync_kind_failed));
                String folder = "USER".equals(line.folder) ? "" : (line.folder + "/");
                text.append('\n').append("  ").append(folder).append(line.name)
                        .append("  ").append(PhotoSyncEngine.formatBytes(line.bytes))
                        .append("  ").append(kind);
            }
            if (result.files.size() > limit) {
                text.append('\n').append(localized.getString(R.string.photo_sync_summary_more,
                        result.files.size() - limit));
            }
        } else {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_empty));
        }
        return text.toString();
    }

    private void showSyncGate(String phase, String text) {
        SyncProgress progress = new SyncProgress();
        progress.active = false;
        progress.phase = phase;
        progress.detail = text == null ? "" : text;
        lastProgress = progress;
        if (hud != null) {
            hud.show();
            hud.bind(progress);
            hud.hideLater(8_000);
        }
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName()));
    }

    private void onEngineProgress(SyncProgress progress) {
        lastProgress = progress;
        Context localized = AppLocale.wrap(this);
        if (progress.fileTotal > 0 && progress.fileName != null && !progress.fileName.isEmpty()) {
            syncStatus = localized.getString(R.string.photos_sync_file,
                    progress.fileIndex, progress.fileTotal, progress.fileName);
        } else {
            syncStatus = localized.getString(R.string.photos_sync_running);
        }
        if (hud != null) hud.bind(progress);
        long now = SystemClock.elapsedRealtime();
        boolean phaseChanged = progress.phase != null && lastProgress != null
                && !progress.phase.equals(lastBroadcastPhase);
        if (phaseChanged || now - lastNotifyAt > 250) {
            lastBroadcastPhase = progress.phase == null ? "" : progress.phase;
            sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName()));
            lastNotifyAt = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    }

    private Network resolveVesperaNetwork() {
        Network current = VesperaConnectionService.getActiveNetwork();
        if (current != null) return current;
        try {
            VesperaConnectionService.refreshConnectedNetwork(this);
        } catch (Exception ignored) {
        }
        current = VesperaConnectionService.getActiveNetwork();
        if (current != null) return current;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network network : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) continue;
            for (LinkAddress addr : lp.getLinkAddresses()) {
                String host = addr.getAddress().getHostAddress();
                if (host != null && host.startsWith("10.0.0.")) return network;
            }
        }
        return null;
    }

    private boolean canReachVesperaFtp(Network network) {
        return FtpProbe.findVesperaControl(network, PhotoSyncEngine.HOST) > 0;
    }

    private void startFtpLocked() {
        File root = DaemonDisk.photosDir(this);
        if (!mounted || root == null || !root.isDirectory()) return;
        if (ftpServer.isRunning()) return;
        try {
            int avoid = telescopeFtp.isRunning() ? telescopeFtp.getPort() : FtpProbe.TELESCOPE_PREFERRED;
            ftpServer.start(root, avoid);
        } catch (Exception failure) {
            Log.w(TAG, "ftp start", failure);
        }
        refreshFtpStatus();
    }

    private void stopFtpLocked() {
        ftpServer.stop();
        refreshFtpStatus();
    }

    private void refreshTelescopeFtpLocked() {
        Network network = resolveVesperaNetwork();
        if (network == null && !isVesperaConnected()) {
            vesperaFtpPort = -1;
            telescopeFtp.stop();
            refreshFtpStatus();
            return;
        }
        vesperaFtpPort = FtpProbe.findVesperaControl(network, PhotoSyncEngine.HOST);
        if (vesperaFtpPort <= 0) {
            telescopeFtp.stop();
            refreshFtpStatus();
            return;
        }
        try {
            int avoid = ftpServer.isRunning() ? ftpServer.getPort() : FtpProbe.HD_PREFERRED;
            telescopeFtp.start(network, PhotoSyncEngine.HOST, vesperaFtpPort, avoid);
        } catch (Exception failure) {
            Log.w(TAG, "telescope ftp", failure);
            telescopeFtp.stop();
        }
        refreshFtpStatus();
    }

    private void refreshFtpStatus() {
        Context localized = AppLocale.wrap(this);
        String vespera;
        if (telescopeFtp.isRunning()) {
            vespera = localized.getString(R.string.photo_ftp_vespera_on,
                    telescopeFtp.getPort(), telescopeFtp.getUpstreamHost(),
                    telescopeFtp.getUpstreamPort());
        } else if (isVesperaConnected() || resolveVesperaNetwork() != null) {
            vespera = localized.getString(R.string.photo_ftp_vespera_off, PhotoSyncEngine.HOST);
        } else {
            vespera = localized.getString(R.string.photo_ftp_vespera_wait);
        }
        String disk = ftpServer.isRunning()
                ? localized.getString(R.string.photo_ftp_hd_on, ftpServer.getPort())
                : localized.getString(R.string.photo_ftp_hd_off);
        ftpStatus = vespera + "\n" + disk;
    }

    private UsbDisk findDisk(String spec) {
        for (String encoded : disksEncoded) {
            UsbDisk disk = UsbDisk.parse(encoded);
            if (disk != null && disk.matches(spec)) return disk;
        }
        return null;
    }

    private boolean isVesperaConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())
                && VesperaConnectionService.getActiveNetwork() != null;
    }

    private String ftpHint() {
        Context localized = AppLocale.wrap(this);
        int scopePort = telescopeFtp.isRunning() ? telescopeFtp.getPort() : FtpProbe.TELESCOPE_PREFERRED;
        int diskPort = ftpServer.isRunning() ? ftpServer.getPort() : FtpProbe.HD_PREFERRED;
        String scopeUrls = joinUrls(ftpUrls(scopePort));
        String diskUrls = joinUrls(ftpUrls(diskPort));
        return localized.getString(R.string.photo_ftp_hint_two,
                scopeUrls, diskUrls, localized.getString(R.string.photos_ftp_anonymous));
    }

    private List<String> ftpUrls(int port) {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                List<NetworkInterface> list = Collections.list(ifs);
                Collections.sort(list, (a, b) -> Integer.compare(rankIface(a.getName()), rankIface(b.getName())));
                for (NetworkInterface nif : list) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address) {
                            urls.add("ftp://" + addr.getHostAddress() + ":" + port
                                    + "/  (" + nif.getName() + ")");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (urls.isEmpty()) {
            urls.add("ftp://<tailscale-ip>:" + port + "/");
        }
        return urls;
    }

    private static String joinUrls(List<String> urls) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) body.append('\n');
            body.append(urls.get(i));
        }
        return body.toString();
    }

    private static int rankIface(String name) {
        if (name == null) return 50;
        String n = name.toLowerCase();
        if (n.contains("tailscale") || n.equals("tun0")) return 0;
        if (n.startsWith("eth")) return 1;
        if (n.startsWith("wlan")) return 10;
        return 20;
    }

    private void publish() {
        Context localized = AppLocale.wrap(this);
        String last = lastSync == null || lastSync.isEmpty()
                ? localized.getString(R.string.photos_never_sync) : lastSync;
        String disksBlob = "";
        if (disksEncoded != null && disksEncoded.length > 0) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < disksEncoded.length; i++) {
                if (i > 0) joined.append('\n');
                joined.append(disksEncoded[i]);
            }
            disksBlob = joined.toString();
        }
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_DISKS, disksBlob)
                .putExtra(EXTRA_HD, hdStatus)
                .putExtra(EXTRA_FTP, ftpStatus)
                .putExtra(EXTRA_SYNC, syncStatus)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_FTP_HINT, ftpHint())
                .putExtra(EXTRA_FILE_COUNT, fileCount)
                .putExtra(EXTRA_LAST_SYNC, last)
                .putExtra(EXTRA_MOUNTED, mounted)
                .putExtra(EXTRA_FTP_RUNNING, ftpServer.isRunning())
                .putExtra(EXTRA_SELECTED, selectedSpec)
                .putExtra(EXTRA_SYNCING, syncing)
                .putExtra(EXTRA_PAUSED, syncStore != null && syncStore.paused());
        sendBroadcast(intent);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (syncStore != null) syncStore.markInterrupted(this);
        restartSelf(shouldResume());
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        boolean resume = syncing || shouldResume();
        if (resume && syncStore != null) syncStore.markInterrupted(this);
        mainHandler.removeCallbacks(tick);
        if (connectionReceiverRegistered) {
            try { unregisterReceiver(connectionReceiver); } catch (Exception ignored) {}
            connectionReceiverRegistered = false;
        }
        ftpServer.stop();
        telescopeFtp.stop();
        if (hud != null) hud.hide();
        worker.shutdownNow();
        syncExecutor.shutdownNow();
        restartSelf(resume);
        super.onDestroy();
    }

    private void restartSelf(boolean resume) {
        try {
            startForegroundService(new Intent(getApplicationContext(), PhotoSyncService.class)
                    .setAction(resume ? ACTION_RESUME : ACTION_BOOTSTRAP));
        } catch (Exception ignored) {
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
