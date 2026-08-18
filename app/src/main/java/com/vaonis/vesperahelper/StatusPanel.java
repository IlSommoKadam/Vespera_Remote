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

/** Tab showing Vespera infrastructure + instrument status from the REST API. */
final class StatusPanel {
    private static final long AUTO_REFRESH_MS = 15_000;

    private final Activity activity;
    private final float density;
    private final VesperaDeviceStore deviceStore;
    private final ScrollView scroll;
    private final TextView infraBlock;
    private final TextView instrumentBlock;
    private final TextView apiError;
    private final TextView lastUpdate;
    private final Button refresh;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private final Runnable autoRefresh = this::refreshIfVisible;
    private boolean visible;
    private String host = "10.0.0.1";
    private int apiPort = -1;

    StatusPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.deviceStore = VesperaDeviceStore.from(activity);

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

        layout.addView(title(activity.getString(R.string.status_tab_title)));
        infraBlock = body("—");
        instrumentBlock = body("—");
        apiError = body("");
        apiError.setTextColor(UiStyle.ROSE);
        lastUpdate = body("");
        lastUpdate.setTextSize(12);

        refresh = action(activity.getString(R.string.status_tab_refresh), UiStyle.SLATE);
        refresh.setOnClickListener(v -> refreshNow());

        layout.addView(section(activity.getString(R.string.status_tab_infra)));
        layout.addView(infraBlock);
        layout.addView(section(activity.getString(R.string.status_tab_instrument)));
        layout.addView(instrumentBlock);
        layout.addView(apiError);
        layout.addView(lastUpdate);
        layout.addView(refresh);
        scroll.addView(layout);
        updateInfraBlock();
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
        visible = true;
        updateInfraBlock();
        refreshNow();
        mainHandler.removeCallbacks(autoRefresh);
        mainHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    void onHidden() {
        visible = false;
        mainHandler.removeCallbacks(autoRefresh);
    }

    void onConnectionChanged() {
        if (visible) {
            updateInfraBlock();
            refreshNow();
        } else {
            updateInfraBlock();
        }
    }

    void shutdown() {
        mainHandler.removeCallbacks(autoRefresh);
        worker.shutdownNow();
    }

    private void refreshIfVisible() {
        if (!visible) return;
        refreshNow();
        mainHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    private void refreshNow() {
        updateInfraBlock();
        if (!isConnected()) {
            instrumentBlock.setText(activity.getString(R.string.status_tab_need_wifi));
            apiError.setText("");
            lastUpdate.setText("");
            return;
        }
        if (!fetchInFlight.compareAndSet(false, true)) {
            return;
        }
        refresh.setEnabled(false);
        apiError.setText(activity.getString(R.string.status_tab_fetching));
        final String fetchHost = host;
        final int fetchPort = apiPort > 0 ? apiPort : InstrumentWatchdog.lastApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        worker.execute(() -> {
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(fetchHost, fetchPort, network);
            mainHandler.post(() -> {
                fetchInFlight.set(false);
                refresh.setEnabled(true);
                if (snap == null) {
                    instrumentBlock.setText(activity.getString(R.string.status_tab_api_unavailable));
                    apiError.setText(activity.getString(R.string.status_tab_error));
                } else {
                    instrumentBlock.setText(formatInstrument(snap));
                    apiError.setText("");
                }
                String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date());
                lastUpdate.setText(activity.getString(R.string.status_tab_last_update, when));
            });
        });
    }

    private void updateInfraBlock() {
        String connection = StatusTexts.connection(activity, VesperaConnectionService.getLastStatus());
        InstrumentWatchdog.Snapshot sing = InstrumentWatchdog.lastSnapshot();
        String singularity = sing != null
                ? sing.message
                : StatusTexts.singularity(activity, InstrumentWatchdog.STATUS_IDLE);
        int port = apiPort > 0 ? apiPort : InstrumentWatchdog.lastApiPort();
        String apiLine = port > 0
                ? activity.getString(R.string.status_tab_api_port_value, port)
                : activity.getString(R.string.status_tab_api_port_unknown);
        int ftpPort = FtpProbe.lastVesperaPort();
        String ftpLine = ftpPort > 0
                ? activity.getString(R.string.status_tab_ftp_port_value, ftpPort)
                : activity.getString(R.string.status_tab_ftp_port_unknown);
        String model = deviceStore.isConfigured() ? deviceStore.getModel() : "—";
        String ssid = deviceStore.isConfigured() ? deviceStore.getSsid() : "—";
        infraBlock.setText(activity.getString(R.string.status_tab_infra_body,
                model, ssid, host, connection, apiLine, ftpLine, singularity));
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
