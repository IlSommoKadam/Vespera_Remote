package com.vaonis.vesperahelper;

import android.app.Activity;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
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

/** Tab Telescopio: stato strumento (REST) + comandi diretti senza Singularity. */
final class TelescopePanel {
    private static final long AUTO_REFRESH_MS = 15_000;

    private final Activity activity;
    private final float density;
    private final ScrollView scroll;
    private final TextView instrumentBlock;
    private final TextView commandResult;
    private final TextView lastUpdate;
    private final Button refreshStatus;
    private final Button scanPorts;
    private final Button cmdPark;
    private final Button cmdStop;
    private final Button cmdInit;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean commandInFlight = new AtomicBoolean(false);
    private final Runnable autoRefresh = this::refreshStatusIfVisible;
    private Runnable onPortScanRequested;
    private boolean visible;
    private String host = "10.0.0.1";
    private int apiPort = -1;
    private volatile VesperaStatusSnapshot lastSnapshot;

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
        instrumentBlock = body("—");
        lastUpdate = body("");
        lastUpdate.setTextSize(12);
        refreshStatus = action(activity.getString(R.string.telescope_btn_refresh_status),
                UiStyle.SLATE);
        refreshStatus.setOnClickListener(v -> refreshStatusNow(true));

        layout.addView(instrumentBlock);
        layout.addView(lastUpdate);
        layout.addView(refreshStatus);

        layout.addView(section(activity.getString(R.string.telescope_section_commands)));
        TextView cmdHint = body(activity.getString(R.string.telescope_commands_hint));
        cmdHint.setTextSize(13);
        layout.addView(cmdHint);
        scanPorts = action(activity.getString(R.string.telescope_btn_scan_ports), UiStyle.SLATE);
        scanPorts.setOnClickListener(v -> requestPortScan());
        cmdPark = action(activity.getString(R.string.telescope_btn_park), UiStyle.TERRACOTTA);
        cmdPark.setOnClickListener(v -> runCommand(VesperaCommandClient.Command.PARK));
        cmdStop = action(activity.getString(R.string.telescope_btn_stop), UiStyle.ROSE);
        cmdStop.setOnClickListener(v -> runCommand(VesperaCommandClient.Command.STOP));
        cmdInit = action(activity.getString(R.string.telescope_btn_init), UiStyle.GREEN);
        cmdInit.setOnClickListener(v -> runCommand(VesperaCommandClient.Command.INIT));
        commandResult = body("");
        commandResult.setTextColor(UiStyle.SLATE);
        layout.addView(scanPorts);
        layout.addView(cmdPark);
        layout.addView(cmdStop);
        layout.addView(cmdInit);
        layout.addView(commandResult);
        scroll.addView(layout);
    }

    View view() {
        return scroll;
    }

    void setPortScanListener(Runnable listener) {
        onPortScanRequested = listener;
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
        visible = true;
        refreshStatusNow(true);
        scheduleAutoRefresh();
    }

    void onHidden() {
        visible = false;
        mainHandler.removeCallbacks(autoRefresh);
    }

    boolean isTabActive() {
        return visible;
    }

    void onConnectionChanged() {
        if (visible) {
            refreshStatusNow(true);
        }
    }

    void shutdown() {
        visible = false;
        mainHandler.removeCallbacks(autoRefresh);
        worker.shutdownNow();
    }

    private void scheduleAutoRefresh() {
        mainHandler.removeCallbacks(autoRefresh);
        if (!visible) return;
        mainHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    private void refreshStatusIfVisible() {
        if (!visible) return;
        refreshStatusNow(true);
        scheduleAutoRefresh();
    }

    private void requestPortScan() {
        if (onPortScanRequested != null) {
            onPortScanRequested.run();
        }
    }

    private void refreshStatusNow(boolean force) {
        if (!force && !visible) return;
        if (!isConnected()) {
            if (!visible) return;
            instrumentBlock.setText(activity.getString(R.string.status_tab_need_wifi));
            lastUpdate.setText("");
            setCommandsEnabled(false);
            return;
        }
        setCommandsEnabled(true);
        if (!fetchInFlight.compareAndSet(false, true)) return;
        refreshStatus.setEnabled(false);
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        worker.execute(() -> {
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(fetchHost, fetchPort, network);
            mainHandler.post(() -> {
                fetchInFlight.set(false);
                if (!visible) {
                    refreshStatus.setEnabled(isConnected());
                    return;
                }
                refreshStatus.setEnabled(true);
                if (snap == null) {
                    instrumentBlock.setText(activity.getString(R.string.status_tab_api_unavailable));
                    lastSnapshot = null;
                } else {
                    lastSnapshot = snap;
                    instrumentBlock.setText(formatInstrument(snap));
                }
                String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date());
                lastUpdate.setText(activity.getString(R.string.status_tab_last_update, when));
            });
        });
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
        final VesperaStatusSnapshot authSnap = lastSnapshot;
        worker.execute(() -> {
            VesperaCommandClient.Result result = VesperaCommandClient.send(
                    fetchHost, fetchPort, network, command, authSnap);
            mainHandler.post(() -> {
                commandInFlight.set(false);
                setCommandsEnabled(true);
                if (result.success) {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_ok, label(command), result.message));
                    if (visible) refreshStatusNow(true);
                } else if ("auth_challenge_missing".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth_challenge));
                } else if ("auth_rejected".equals(result.message)
                        || "auth_required".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth_fail));
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
            case INIT: return activity.getString(R.string.telescope_btn_init);
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
        scanPorts.setEnabled(on);
        cmdPark.setEnabled(on);
        cmdStop.setEnabled(on);
        cmdInit.setEnabled(on);
    }

    private String formatInstrument(VesperaStatusSnapshot snap) {
        StringBuilder out = new StringBuilder();
        appendLine(out, activity.getString(R.string.status_tab_field_endpoint), snap.endpoint);
        if (!snap.telescopeId.isEmpty()) {
            appendLine(out, activity.getString(R.string.status_tab_field_id), snap.telescopeId);
        }
        if (!snap.model.isEmpty()) {
            appendLine(out, activity.getString(R.string.status_tab_field_model), snap.model);
        }
        if (!snap.state.isEmpty()) {
            appendLine(out, activity.getString(R.string.status_tab_field_state), snap.state);
        }
        appendLine(out, activity.getString(R.string.status_tab_field_initialized),
                snap.initialized
                        ? activity.getString(R.string.status_tab_yes)
                        : activity.getString(R.string.status_tab_no));
        if (!snap.operationType.isEmpty()) {
            appendLine(out, activity.getString(R.string.status_tab_field_operation),
                    snap.operationType);
        }
        if (!snap.targetName.isEmpty()) {
            appendLine(out, activity.getString(R.string.status_tab_field_target), snap.targetName);
        }
        if (snap.stackingCount > 0) {
            appendLine(out, activity.getString(R.string.status_tab_field_stacking),
                    String.valueOf(snap.stackingCount));
        }
        if (snap.exposureMicroSec > 0) {
            double sec = snap.exposureMicroSec / 1_000_000.0;
            appendLine(out, activity.getString(R.string.status_tab_field_exposure),
                    String.format(Locale.US, "%.1f s", sec));
        }
        if (snap.gain > 0) {
            appendLine(out, activity.getString(R.string.status_tab_field_gain),
                    String.valueOf(snap.gain));
        }
        if (snap.batteryPercent >= 0) {
            String battery = snap.batteryPercent + "%";
            if (!snap.batteryStatus.isEmpty()) {
                battery += " (" + snap.batteryStatus + ")";
            }
            appendLine(out, activity.getString(R.string.status_tab_field_battery), battery);
        }
        if (!snap.hasInstrumentFields()) {
            String compact = snap.rawJson;
            if (compact.length() > 600) {
                compact = compact.substring(0, 600) + "…";
            }
            appendLine(out, activity.getString(R.string.status_tab_field_raw), compact);
        }
        return out.toString().trim();
    }

    private static void appendLine(StringBuilder out, String label, String value) {
        if (value == null || value.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(label).append(": ").append(value);
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
