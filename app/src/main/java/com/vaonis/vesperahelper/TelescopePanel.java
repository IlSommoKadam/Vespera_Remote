package com.vaonis.vesperahelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tab Telescopio: stato live Socket.IO 8083 + comandi REST 8082. */
final class TelescopePanel {
    private static final long AUTO_REFRESH_MS = 30_000;
    private static final long PHOTO_USAGE_RETRY_MS = 60_000;

    private final Activity activity;
    private final float density;
    private final ScrollView scroll;
    private final LinearLayout statusBox;
    private final TextView commandResult;
    private final TextView lastUpdate;
    private final TextView portInventory;
    private final Button refreshStatus;
    private final Button cmdPark;
    private final Button cmdStop;
    private final Button cmdResume;
    private final Button cmdInit;
    private final Button cmdShutdown;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService storageWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService liveWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean commandInFlight = new AtomicBoolean(false);
    private final AtomicBoolean liveRunning = new AtomicBoolean(false);
    private final AtomicBoolean photoUsageInFlight = new AtomicBoolean(false);
    private final Runnable autoRefresh = this::refreshStatusIfVisible;
    private VesperaSocketIo liveClient;
    private volatile boolean liveOk;
    private boolean visible;
    private String host = "10.0.0.1";
    private int apiPort = -1;
    private AlertDialog powerDialog;
    private boolean powerWarningIgnoredThisVisit;
    private boolean powerWarningAccepted;
    private VesperaStatusSnapshot lastSnap;
    private VesperaInternalStorage.Usage photoUsage;
    private long lastPhotoUsageAt;
    private boolean photoUsageForceOnOpen;
    private boolean photoUsageChecking;

    TelescopePanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVisibility(View.GONE);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        layout.addView(title(activity.getString(R.string.telescope_tab_title)));
        layout.addView(section(activity.getString(R.string.telescope_section_status)));
        statusBox = new LinearLayout(activity);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        int boxPad = Math.max(8, Math.round(10 * density));
        statusBox.setPadding(boxPad, boxPad, boxPad, boxPad);
        statusBox.setBackground(UiStyle.recessedStatus(0xFFECEFF1, density));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = (int) (8 * density);
        statusBox.setLayoutParams(boxLp);
        addStatusRow(activity.getString(R.string.status_tab_field_state), "—");
        lastUpdate = body("");
        lastUpdate.setTextSize(12);
        refreshStatus = action(activity.getString(R.string.telescope_btn_refresh_status),
                UiStyle.SLATE);
        refreshStatus.setOnClickListener(v -> confirmRefresh());
        scroll.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);

        layout.addView(statusBox);
        layout.addView(lastUpdate);
        layout.addView(refreshStatus);

        layout.addView(section(activity.getString(R.string.telescope_section_commands)));
        TextView cmdHint = body(activity.getString(R.string.telescope_commands_hint));
        cmdHint.setTextSize(13);
        layout.addView(cmdHint);
        cmdPark = action(activity.getString(R.string.telescope_btn_park), UiStyle.TERRACOTTA);
        cmdPark.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.PARK));
        cmdStop = action(activity.getString(R.string.telescope_btn_stop), UiStyle.INK);
        cmdStop.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.STOP));
        cmdResume = action(activity.getString(R.string.telescope_btn_resume), UiStyle.STEEL_BLUE);
        cmdResume.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.RESUME));
        cmdInit = action(activity.getString(R.string.telescope_btn_init), UiStyle.GREEN);
        cmdInit.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.INIT));
        cmdShutdown = action(activity.getString(R.string.telescope_btn_shutdown), UiStyle.ROSE);
        cmdShutdown.setOnClickListener(v -> confirmShutdown());
        LinearLayout.LayoutParams shutdownLp =
                (LinearLayout.LayoutParams) cmdShutdown.getLayoutParams();
        shutdownLp.topMargin = (int) (8 * density);
        cmdShutdown.setLayoutParams(shutdownLp);
        commandResult = body("");
        commandResult.setTextColor(UiStyle.SLATE);
        layout.addView(cmdPark);
        layout.addView(cmdStop);
        layout.addView(cmdResume);
        layout.addView(cmdInit);
        layout.addView(cmdShutdown);
        layout.addView(commandResult);
        layout.addView(section(activity.getString(R.string.telescope_section_ports)));
        portInventory = body(activity.getString(R.string.port_inventory_idle));
        portInventory.setTextSize(13);
        layout.addView(portInventory);
        scroll.addView(layout);
        updateObservationButtons(false);
    }

    View view() {
        return scroll;
    }

    void setHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            this.host = host.trim();
        }
    }

    void setApiPort(int port) {
        this.apiPort = port;
    }

    void onVisible() {
        boolean alreadyVisible = visible;
        visible = true;
        powerWarningIgnoredThisVisit = false;
        photoUsageForceOnOpen = true;
        refreshPortInventory();
        refreshStatusNow(!alreadyVisible);
        startLive();
        scheduleAutoRefresh();
    }

    void onHidden() {
        visible = false;
        stopLive();
        mainHandler.removeCallbacks(autoRefresh);
        dismissPowerWarning(false);
    }

    boolean isTabActive() {
        return visible;
    }

    void onConnectionChanged() {
        refreshPortInventory();
        stopLive();
        if (visible) {
            refreshStatusNow(false);
            startLive();
        }
    }

    void showPortScanRunning() {
        portInventory.setText(activity.getString(R.string.port_scan_running));
    }

    void updatePortInventory(VesperaPortScan scan) {
        portInventory.setText(VesperaPortInventory.formatFull(activity, scan));
    }

    void clearPortInventory() {
        portInventory.setText(activity.getString(R.string.port_inventory_idle));
    }

    private void refreshPortInventory() {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null) {
            updatePortInventory(scan);
        } else {
            clearPortInventory();
        }
    }

    void shutdown() {
        visible = false;
        stopLive();
        mainHandler.removeCallbacks(autoRefresh);
        worker.shutdownNow();
        storageWorker.shutdownNow();
        liveWorker.shutdownNow();
        dismissPowerWarning(false);
    }

    private void scheduleAutoRefresh() {
        mainHandler.removeCallbacks(autoRefresh);
        if (!visible) return;
        mainHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    private void refreshStatusIfVisible() {
        if (!visible) return;
        if (!liveOk) refreshStatusNow(false);
        scheduleAutoRefresh();
    }

    private void startLive() {
        if (!visible || !isConnected()) {
            stopLive();
            return;
        }
        if (!liveRunning.compareAndSet(false, true)) return;
        liveOk = false;
        final String fetchHost = host;
        final Network network = VesperaConnectionService.getActiveNetwork();
        liveClient = new VesperaSocketIo(new VesperaSocketIo.Listener() {
            @Override
            public void onStatus(VesperaStatusSnapshot snapshot) {
                mainHandler.post(() -> applyLiveSnapshot(snapshot));
            }

            @Override
            public void onInfo(String message) {
                Log.d("VesperaSIO", message);
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    liveOk = false;
                    if (visible && !fetchInFlight.get()) refreshStatusNow(false);
                });
            }
        });
        final VesperaSocketIo client = liveClient;
        liveWorker.execute(() -> {
            try {
                client.run(fetchHost, network);
            } finally {
                if (liveClient == client) {
                    liveRunning.set(false);
                    liveOk = false;
                }
            }
        });
    }

    private void stopLive() {
        liveOk = false;
        VesperaSocketIo client = liveClient;
        liveClient = null;
        if (client != null) client.stop();
        liveRunning.set(false);
    }

    private void applyLiveSnapshot(VesperaStatusSnapshot snapshot) {
        if (!visible || snapshot == null) return;
        liveOk = true;
        setCommandsEnabled(true);
        String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date());
        setStatusSnapshot(snapshot,
                activity.getString(R.string.status_tab_last_update, when)
                        + " · " + activity.getString(R.string.status_tab_live));
        maybeShowPowerWarning(snapshot);
        updateObservationButtons(snapshot.isObserving());
    }

    private void refreshStatusNow(boolean userInitiated) {
        if (!visible && !userInitiated) return;
        if (!isConnected()) {
            stopLive();
            if (!visible) return;
            setStatusMessage(activity.getString(R.string.status_tab_need_wifi), "");
            setCommandsEnabled(false);
            return;
        }
        setCommandsEnabled(true);
        if (!fetchInFlight.compareAndSet(false, true)) return;
        if (userInitiated) {
            refreshStatus.setEnabled(false);
            setStatusMessage(activity.getString(R.string.status_tab_fetching),
                    lastUpdate.getText().toString());
        }
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        worker.execute(() -> {
            VesperaStatusClient.Result result = VesperaStatusClient.fetchResult(
                    fetchHost, fetchPort, network);
            mainHandler.post(() -> {
                fetchInFlight.set(false);
                refreshStatus.setEnabled(isConnected());
                if (!visible) return;
                String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date());
                if (result.snapshot == null) {
                    setStatusMessage(formatUnavailable(result.error),
                            activity.getString(R.string.status_tab_last_update, when));
                } else {
                    setStatusSnapshot(result.snapshot,
                            activity.getString(R.string.status_tab_last_update, when));
                    maybeShowPowerWarning(result.snapshot);
                    updateObservationButtons(result.snapshot.isObserving());
                }
            });
        });
    }

    /** Updates status without scrolling the tab back to the top. */
    private void setStatusSnapshot(VesperaStatusSnapshot snap, String updated) {
        lastSnap = snap;
        maybeProbePhotoUsage(snap);
        refillStatusKeepingScroll();
        if (updated != null) lastUpdate.setText(updated);
        maybeSyncIfStorageFull(snap);
    }

    private void refillStatusKeepingScroll() {
        if (!visible || lastSnap == null) return;
        final int x = scroll.getScrollX();
        final int y = scroll.getScrollY();
        statusBox.removeAllViews();
        fillStatusRows(lastSnap);
        scroll.post(() -> scroll.scrollTo(x, y));
    }

    private void setStatusMessage(String status, String updated) {
        final int x = scroll.getScrollX();
        final int y = scroll.getScrollY();
        statusBox.removeAllViews();
        addStatusMessage(status == null ? "—" : status);
        if (updated != null) lastUpdate.setText(updated);
        scroll.post(() -> scroll.scrollTo(x, y));
    }

    private void fillStatusRows(VesperaStatusSnapshot snap) {
        addStatusRow(activity.getString(R.string.status_tab_field_state),
                snap.state.isEmpty() ? "—" : snap.state);
        addStatusRow(activity.getString(R.string.status_tab_field_initialized),
                snap.initialized
                        ? activity.getString(R.string.status_tab_yes)
                        : activity.getString(R.string.status_tab_no));
        addStatusRow(activity.getString(R.string.status_tab_field_observation),
                observationLabel(snap));
        addStatusRow(activity.getString(R.string.status_tab_field_operation), snap.operationType);
        addStatusRow(activity.getString(R.string.status_tab_field_step), snap.step);
        addStatusRow(activity.getString(R.string.status_tab_field_tracking),
                trackingLabel(snap.tracking));
        addStatusRow(activity.getString(R.string.status_tab_field_motors), snap.motors);
        addStatusRow(activity.getString(R.string.status_tab_field_focus), snap.focus);
        if (!snap.targetName.isEmpty()) {
            addStatusRow(activity.getString(R.string.status_tab_field_target), snap.targetName);
        } else if (VesperaLastTarget.hasTarget() && !VesperaLastTarget.label().isEmpty()) {
            addStatusRow(activity.getString(R.string.status_tab_field_last_target),
                    VesperaLastTarget.label());
        }
        addStatusRow(activity.getString(R.string.status_tab_field_coordinates), snap.coordinates);
        addStatusRow(activity.getString(R.string.status_tab_field_location), snap.location);
        if (snap.stackingCount > 0) {
            addStatusRow(activity.getString(R.string.status_tab_field_stacking),
                    String.valueOf(snap.stackingCount));
        }
        if (snap.exposureMicroSec > 0) {
            double sec = snap.exposureMicroSec / 1_000_000.0;
            addStatusRow(activity.getString(R.string.status_tab_field_exposure),
                    String.format(Locale.US, "%.1f s", sec));
        }
        if (snap.gain > 0) {
            addStatusRow(activity.getString(R.string.status_tab_field_gain),
                    String.valueOf(snap.gain));
        }
        addStatusRow(activity.getString(R.string.status_tab_field_filter), snap.filter);
        addStatusRow(activity.getString(R.string.status_tab_field_temperature), snap.temperature);
        addStatusRow(activity.getString(R.string.status_tab_field_firmware), snap.firmware);
        addStatusRow(activity.getString(R.string.status_tab_field_error), snap.error);
        addStatusRow(activity.getString(R.string.status_tab_field_storage), storageLabel(snap));
        if (snap.batteryPercent >= 0) {
            String battery = snap.batteryPercent + "%";
            if (!snap.batteryStatus.isEmpty()) {
                battery += " (" + snap.batteryStatus + ")";
            }
            addStatusRow(activity.getString(R.string.status_tab_field_battery), battery);
        }
        if (!snap.hasInstrumentFields()) {
            String compact = snap.rawJson;
            if (compact.length() > 600) compact = compact.substring(0, 600) + "…";
            addStatusRow(activity.getString(R.string.status_tab_field_raw), compact);
        }
    }

    private String storageLabel(VesperaStatusSnapshot snap) {
        if (snap.storageUsedPercent >= PhotoSyncService.STORAGE_SYNC_PERCENT) {
            String base = snap.storage.isEmpty()
                    ? (snap.storageUsedPercent + "%") : snap.storage;
            return activity.getString(R.string.status_tab_storage_full, base);
        }
        if (!snap.storage.isEmpty()) return snap.storage;
        if (snap.storageUsedPercent >= 0) return snap.storageUsedPercent + "%";
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.lastKnown();
        if (usage != null) photoUsage = usage;
        if (photoUsage != null && !photoUsage.label.isEmpty()) {
            if (photoUsage.usedPercent >= PhotoSyncService.STORAGE_SYNC_PERCENT) {
                return activity.getString(R.string.status_tab_storage_full, photoUsage.label);
            }
            return photoUsage.label;
        }
        if (photoUsageChecking) {
            return activity.getString(R.string.status_tab_storage_checking);
        }
        String err = VesperaInternalStorage.lastError();
        if (!err.isEmpty()) {
            return activity.getString(R.string.status_tab_storage_ftp_fail, err);
        }
        return "—";
    }

    private void maybeProbePhotoUsage(VesperaStatusSnapshot snap) {
        if (!visible || snap == null || !isConnected()) return;
        if (snap.storageUsedPercent >= 0 && !snap.storage.isEmpty()) {
            photoUsageForceOnOpen = false;
            photoUsageChecking = false;
            return;
        }
        boolean force = photoUsageForceOnOpen;
        photoUsageForceOnOpen = false;
        long now = System.currentTimeMillis();
        if (!force) {
            if (photoUsage != null || VesperaInternalStorage.lastKnown() != null) return;
            if (lastPhotoUsageAt > 0 && now - lastPhotoUsageAt < PHOTO_USAGE_RETRY_MS) return;
        }
        if (!photoUsageInFlight.compareAndSet(false, true)) {
            photoUsageChecking = true;
            return;
        }
        lastPhotoUsageAt = now;
        photoUsageChecking = true;
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final String model = snap.model;
        final Network network = VesperaConnectionService.getActiveNetwork();
        storageWorker.execute(() -> {
            VesperaInternalStorage.Usage usage = VesperaInternalStorage.probe(
                    network, fetchHost, fetchPort, model);
            mainHandler.post(() -> {
                photoUsageInFlight.set(false);
                photoUsageChecking = false;
                if (usage != null) photoUsage = usage;
                if (!visible || lastSnap == null) return;
                refillStatusKeepingScroll();
                maybeSyncIfStorageFull(lastSnap);
            });
        });
    }

    private void maybeSyncIfStorageFull(VesperaStatusSnapshot snap) {
        int percent = snap == null ? -1 : snap.storageUsedPercent;
        if (percent < 0 && photoUsage != null) percent = photoUsage.usedPercent;
        if (percent < PhotoSyncService.STORAGE_SYNC_PERCENT) return;
        PhotoSyncService.syncIfStorageHigh(activity, percent);
    }

    private String observationLabel(VesperaStatusSnapshot snap) {
        if ("RUNNING".equals(snap.observationStatus)) {
            return activity.getString(R.string.status_tab_observation_running);
        }
        if ("FINISHED".equals(snap.observationStatus)) {
            return activity.getString(R.string.status_tab_observation_finished);
        }
        if ("STOPPED".equals(snap.observationStatus) || snap.canResumeObservation()) {
            return activity.getString(R.string.status_tab_observation_stopped);
        }
        return "";
    }

    private String trackingLabel(String tracking) {
        if ("ON".equals(tracking)) return activity.getString(R.string.status_tab_tracking_on);
        if ("STARTING".equals(tracking)) {
            return activity.getString(R.string.status_tab_tracking_starting);
        }
        return activity.getString(R.string.status_tab_tracking_off);
    }

    private void addStatusRow(String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.max(8, Math.round(8 * density));
        int padV = Math.max(6, Math.round(6 * density));
        row.setPadding(padH, padV, padH, padV);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xFFF7FAFC);
        chip.setCornerRadius(Math.round(6 * density));
        row.setBackground(chip);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.max(4, Math.round(4 * density));
        row.setLayoutParams(rowLp);

        int labelWidth = Math.round(152 * density);
        TextView name = statusCell(label, 0xFF546E7A, false);
        name.setLayoutParams(new LinearLayout.LayoutParams(labelWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        name.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView colon = statusCell(" : ", 0xFF546E7A, false);
        colon.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView state = statusCell(value, 0xFF1A237E, true);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        state.setLayoutParams(stateLp);
        state.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(name);
        row.addView(colon);
        row.addView(state);
        statusBox.addView(row);
    }

    private void addStatusMessage(String message) {
        TextView view = statusCell(message, 0xFF37474F, false);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusBox.addView(view);
    }

    private TextView statusCell(String text, int color, boolean emphasize) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.SANS_SERIF, emphasize ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextColor(color);
        view.setFocusable(false);
        return view;
    }

    private void runCommand(VesperaCommandClient.Command command) {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        if (!commandInFlight.compareAndSet(false, true)) return;
        setCommandsEnabled(false);
        commandResult.setText(activity.getString(R.string.telescope_command_running));
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        worker.execute(() -> {
            VesperaCommandClient.Result result = VesperaCommandClient.send(
                    fetchHost, fetchPort, network, command);
            mainHandler.post(() -> {
                commandInFlight.set(false);
                setCommandsEnabled(true);
                if (result.success) {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_ok, label(command), result.message));
                    if (command == VesperaCommandClient.Command.STOP) {
                        updateObservationButtons(false);
                    } else if (command == VesperaCommandClient.Command.RESUME) {
                        updateObservationButtons(true);
                    }
                    if (visible) refreshStatusNow(false);
                } else if ("auth_required".equals(result.message)
                        || "auth_sign_failed".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth));
                } else if (result.message.startsWith("status_unavailable")) {
                    commandResult.setText(formatUnavailable(result.message));
                } else if ("auth_missing_challenge".equals(result.message)) {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_auth_missing_challenge));
                } else if ("auth_missing_id".equals(result.message)
                        || "auth_missing".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth_missing));
                } else if ("no_target".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_no_target));
                } else {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_fail, label(command), result.message));
                }
            });
        });
    }

    private String label(VesperaCommandClient.Command command) {
        switch (command) {
            case PARK: return activity.getString(R.string.telescope_btn_park);
            case STOP: return activity.getString(R.string.telescope_btn_stop);
            case RESUME: return activity.getString(R.string.telescope_btn_resume);
            case INIT: return activity.getString(R.string.telescope_btn_init);
            case SHUTDOWN: return activity.getString(R.string.telescope_btn_shutdown);
            default: return command.path;
        }
    }

    private int resolveApiPort() {
        if (apiPort > 0) return apiPort;
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) return scan.apiRestPort;
        return InstrumentWatchdog.lastApiPort();
    }

    private void setCommandsEnabled(boolean enabled) {
        boolean busy = commandInFlight.get();
        boolean on = enabled && !busy;
        cmdPark.setEnabled(on);
        cmdStop.setEnabled(on);
        cmdResume.setEnabled(on);
        cmdInit.setEnabled(on);
        cmdShutdown.setEnabled(on);
    }

    private void updateObservationButtons(boolean observing) {
        cmdStop.setVisibility(observing ? View.VISIBLE : View.GONE);
        cmdResume.setVisibility(observing ? View.GONE : View.VISIBLE);
    }

    private void confirmRefresh() {
        confirmAction(R.string.telescope_btn_refresh_status,
                R.string.telescope_confirm_refresh,
                () -> refreshStatusNow(true));
    }

    private void confirmCommand(VesperaCommandClient.Command command) {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        confirmAction(label(command), activity.getString(confirmMessage(command)),
                () -> runCommand(command));
    }

    private void confirmShutdown() {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        confirmAction(activity.getString(R.string.telescope_shutdown_title),
                activity.getString(R.string.telescope_shutdown_message),
                () -> runCommand(VesperaCommandClient.Command.SHUTDOWN));
    }

    private int confirmMessage(VesperaCommandClient.Command command) {
        switch (command) {
            case PARK: return R.string.telescope_confirm_park;
            case STOP: return R.string.telescope_confirm_stop;
            case RESUME: return R.string.telescope_confirm_resume;
            case INIT: return R.string.telescope_confirm_init;
            case SHUTDOWN: return R.string.telescope_shutdown_message;
            default: return R.string.telescope_confirm_generic;
        }
    }

    private void confirmAction(int titleRes, int messageRes, Runnable onConfirm) {
        confirmAction(activity.getString(titleRes), activity.getString(messageRes), onConfirm);
    }

    private void confirmAction(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.telescope_confirm_ok, (d, w) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String formatUnavailable(String detail) {
        String base = activity.getString(R.string.status_tab_api_unavailable);
        if (detail == null || detail.isEmpty()) return base;
        return base + "\n" + detail;
    }

    private void maybeShowPowerWarning(VesperaStatusSnapshot snap) {
        if (!visible || snap == null || activity.isFinishing()) return;
        if (snap.isOnMainsPower()) {
            powerWarningAccepted = false;
            dismissPowerWarning(true);
            return;
        }
        if (!snap.isOffMainsPower()) return;
        if (powerWarningAccepted || powerWarningIgnoredThisVisit) return;
        if (powerDialog != null && powerDialog.isShowing()) return;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.telescope_power_title)
                .setMessage(R.string.telescope_power_message)
                .setPositiveButton(R.string.telescope_power_accept, (d, w) -> {
                    powerWarningAccepted = true;
                    powerWarningIgnoredThisVisit = false;
                })
                .setNegativeButton(R.string.telescope_power_ignore, (d, w) -> {
                    powerWarningAccepted = false;
                    powerWarningIgnoredThisVisit = true;
                })
                .setOnCancelListener(d -> {
                    powerWarningAccepted = false;
                    powerWarningIgnoredThisVisit = true;
                })
                .create();
        powerDialog = dialog;
        dialog.show();
    }

    private void dismissPowerWarning(boolean resetIgnore) {
        AlertDialog dialog = powerDialog;
        powerDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (resetIgnore) powerWarningIgnoredThisVisit = false;
    }

    private boolean isConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(
                VesperaConnectionService.getLastStatus());
    }

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(17);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(0xFF1A237E);
        UiStyle.spaceBelow(view, density);
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(UiStyle.SLATE);
        UiStyle.spaceBelow(view, density * 0.5f);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xFF37474F);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        UiStyle.applyRecessed(view, 0xFFECEFF1);
        UiStyle.spaceBelow(view, density);
        return view;
    }

    private Button action(String text, int color) {
        Button button = new Button(activity);
        button.setAllCaps(true);
        button.setText(text);
        UiStyle.applyRaised(button, color, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        button.setLayoutParams(lp);
        return button;
    }
}
