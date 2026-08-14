package com.vaonis.vesperawifihelper;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Selects a Vespera and requests its system-wide Wi-Fi connection. */
public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST_CODE = 100;
    private static final int SCAN_PERMISSION_REQUEST_CODE = 102;
    private WifiManager wifiManager;
    private LocationManager locationManager;
    private VesperaDeviceStore deviceStore;
    private TextView versionLabel;
    private TextView status;
    private TextView vesperaInfo;
    private LinearLayout scanLegendRow;
    private TextView connectionInfo;
    private TextView configuredDeviceInfo;
    private TextView deviceTitle;
    private LinearLayout savedDevicePanel;
    private Button deviceStatusBar;
    private LinearLayout foundDevicesList;
    private EditText ssidInput;
    private EditText bssidInput;
    private Button saveManual;
    private Button clearDevice;
    private Button locationSettings;
    private Button refresh;
    private Button connect;
    private Button disconnect;
    private Spinner languageSpinner;
    private boolean languageSpinnerReady;
    /** True when the saved instrument is currently seen in Wi-Fi scan. */
    private boolean savedDeviceOnline;
    /** True from Connect tap until the service reports a terminal status. */
    private boolean connectRequested;
    private static final int COLOR_OFFLINE = 0xFF9E9E9E;
    private static final int COLOR_DETECTED = 0xFFF9A825;
    private static final int COLOR_CONNECTING = 0xFF1565C0;
    private static final int COLOR_CONNECTED = 0xFF2E7D32;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean scanReceiverRegistered;
    private final BroadcastReceiver scanResultsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            showVesperaScanResult(updated);
        }
    };
    private boolean statusReceiverRegistered;
    private final BroadcastReceiver connectionStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String connectionStatus = intent.getStringExtra(VesperaConnectionService.EXTRA_STATUS);
            if (connectionStatus != null) {
                setConnectionState(connectionStatus);
                if (VesperaConnectionService.STATUS_CONNECTED.equals(connectionStatus)) {
                    VesperaConnectionService.requestDaemonRoute(MainActivity.this);
                } else if (VesperaConnectionService.STATUS_DISCONNECTED.equals(connectionStatus)
                        || VesperaConnectionService.STATUS_LOST.equals(connectionStatus)
                        || VesperaConnectionService.STATUS_UNAVAILABLE.equals(connectionStatus)) {
                    clearStaleReachabilityStatus(connectionStatus);
                }
            }
        }
    };

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifiManager = getSystemService(WifiManager.class);
        locationManager = getSystemService(LocationManager.class);
        deviceStore = VesperaDeviceStore.from(this);
        buildUi();
        refreshConfiguredDeviceLabel();
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (16 * density);
        int bottomPadding = (int) (120 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(padding, padding, padding, padding);
        header.setBackgroundColor(0xFFE8EEF4);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        versionLabel = new TextView(this);
        versionLabel.setText(getString(R.string.app_name) + " " + appVersionLabel());
        versionLabel.setTextSize(16);
        versionLabel.setTypeface(versionLabel.getTypeface(), android.graphics.Typeface.BOLD);
        versionLabel.setTextColor(0xFF1A237E);
        versionLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        languageSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> langAdapter = ArrayAdapter.createFromResource(
                this, R.array.language_entries, android.R.layout.simple_spinner_item);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(langAdapter);
        languageSpinner.setSelection(AppLocale.indexOf(AppLocale.getLanguage(this)), false);
        languageSpinner.setBackgroundResource(R.drawable.bg_language_spinner);
        int hPad = (int) (10 * density);
        int vPad = (int) (6 * density);
        languageSpinner.setPaddingRelative(hPad, vPad, (int) (28 * density), vPad);
        languageSpinner.setMinimumWidth((int) (110 * density));
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        languageSpinner.setLayoutParams(langLp);
        languageSpinnerReady = true;
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                                 int position, long id) {
                if (!languageSpinnerReady) return;
                String selected = AppLocale.languageAt(position);
                if (selected.equals(AppLocale.getLanguage(MainActivity.this))) return;
                AppLocale.setLanguage(MainActivity.this, selected);
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        header.addView(versionLabel);
        header.addView(languageSpinner);

        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(0xFF90A4AE);
        headerDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (2 * density))));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(padding, padding / 2, padding, bottomPadding);
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText(R.string.status_configure);
        connectionInfo = new TextView(this);
        connectionInfo.setText(getString(R.string.connection_state,
                StatusTexts.connection(this, VesperaConnectionService.STATUS_DISCONNECTED)));
        configuredDeviceInfo = new TextView(this);
        vesperaInfo = new TextView(this);
        vesperaInfo.setText(R.string.scan_hint);
        scanLegendRow = buildScanLegendRow(density);
        deviceStatusBar = new Button(this);
        deviceStatusBar.setAllCaps(false);
        showIdleDeviceBar();
        foundDevicesList = new LinearLayout(this);
        foundDevicesList.setOrientation(LinearLayout.VERTICAL);

        deviceTitle = new TextView(this);
        deviceTitle.setText(R.string.device_empty_state);

        savedDevicePanel = new LinearLayout(this);
        savedDevicePanel.setOrientation(LinearLayout.VERTICAL);
        int panelPad = (int) (12 * density);
        savedDevicePanel.setPadding(panelPad, panelPad, panelPad, panelPad);
        savedDevicePanel.setBackgroundColor(0xFFDCEBFA);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.START;
        panelLp.topMargin = (int) (4 * density);
        panelLp.bottomMargin = (int) (8 * density);
        savedDevicePanel.setLayoutParams(panelLp);
        savedDevicePanel.addView(configuredDeviceInfo);

        ssidInput = new EditText(this);
        ssidInput.setHint(R.string.hint_ssid);
        ssidInput.setText(deviceStore.getSsid());
        ssidInput.setSingleLine(true);
        bssidInput = new EditText(this);
        bssidInput.setHint(R.string.hint_bssid);
        bssidInput.setText(deviceStore.getBssid());
        bssidInput.setSingleLine(true);
        saveManual = new Button(this);
        saveManual.setText(R.string.btn_save_manual);
        saveManual.setOnClickListener(v -> saveManualDevice());
        clearDevice = new Button(this);
        clearDevice.setText(R.string.btn_clear_device);
        clearDevice.setOnClickListener(v -> {
            deviceStore.clear();
            ssidInput.setText("");
            bssidInput.setText("");
            refreshConfiguredDeviceLabel();
            show(getString(R.string.device_cleared));
            showVesperaScanResult(false);
        });

        locationSettings = new Button(this);
        locationSettings.setText(R.string.btn_location);
        locationSettings.setOnClickListener(v -> openLocationSettings());
        refresh = new Button(this);
        refresh.setText(R.string.btn_scan);
        refresh.setOnClickListener(v -> refreshVesperaScan());
        connect = new Button(this);
        connect.setText(R.string.btn_connect);
        connect.setOnClickListener(v -> connect());
        disconnect = new Button(this);
        disconnect.setText(R.string.btn_disconnect);
        disconnect.setOnClickListener(v -> disconnect());
        refreshConnectButtons();
        layout.addView(status);
        layout.addView(connectionInfo);
        layout.addView(deviceTitle);
        layout.addView(savedDevicePanel);
        layout.addView(ssidInput);
        layout.addView(bssidInput);
        layout.addView(saveManual);
        layout.addView(clearDevice);
        layout.addView(locationSettings);
        layout.addView(refresh);
        layout.addView(vesperaInfo);
        layout.addView(scanLegendRow);
        layout.addView(deviceStatusBar);
        layout.addView(foundDevicesList);

        int actionGap = (int) (12 * density);
        View actionDivider = new View(this);
        actionDivider.setBackgroundColor(0xFFCFD8DC);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (1 * density)));
        dividerLp.topMargin = actionGap;
        dividerLp.bottomMargin = actionGap;
        actionDivider.setLayoutParams(dividerLp);
        layout.addView(actionDivider);

        layout.addView(connect);
        layout.addView(disconnect);
        scroll.addView(layout);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetBottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insetBottom);
            return insets.consumeSystemWindowInsets();
        });

        root.addView(header);
        root.addView(headerDivider);
        root.addView(scroll);
        setContentView(root);
    }

    private String appVersionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "v?";
        }
    }

    private void refreshConfiguredDeviceLabel() {
        if (deviceStore.isConfigured()) {
            String model = deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel();
            deviceTitle.setText(getString(R.string.device_saved_state, model));
            String freq = deviceStore.getFrequencyMhz() > 0
                    ? getString(R.string.channel_mhz, deviceStore.getFrequencyMhz())
                    : getString(R.string.channel_auto);
            configuredDeviceInfo.setText(getString(R.string.device_detail_body,
                    deviceStore.getSsid(), deviceStore.getBssid(), freq));
            savedDevicePanel.setVisibility(View.VISIBLE);
            savedDevicePanel.setBackgroundColor(0xFFDCEBFA);
            status.setText(getString(R.string.ready_for, deviceStore.getModel(), deviceStore.getSsid()));
        } else {
            deviceTitle.setText(R.string.device_empty_state);
            configuredDeviceInfo.setText(R.string.not_saved_label);
            savedDevicePanel.setVisibility(View.GONE);
            status.setText(R.string.status_configure);
        }
    }

    private void saveManualDevice() {
        String ssid = ssidInput.getText().toString().trim();
        String bssid = bssidInput.getText().toString().trim();
        if (!VesperaDeviceStore.isVesperaSsid(ssid)) {
            show(getString(R.string.ssid_invalid));
            return;
        }
        if (!bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
            show(getString(R.string.bssid_invalid));
            return;
        }
        deviceStore.save(ssid, bssid, deviceStore.getFrequencyMhz());
        refreshConfiguredDeviceLabel();
        show(getString(R.string.device_saved, deviceStore.getModel(), ssid));
        showVesperaScanResult(false);
    }

    private void selectScannedDevice(ScanResult result) {
        deviceStore.saveFromScan(result);
        ssidInput.setText(result.SSID);
        bssidInput.setText(result.BSSID == null ? "" : result.BSSID.toLowerCase(Locale.US));
        refreshConfiguredDeviceLabel();
        show(getString(R.string.device_selected,
                VesperaDeviceStore.guessModel(result.SSID), result.SSID));
        showVesperaScanResult(false);
    }

    private void refreshVesperaScan() {
        if (!hasWifiPermissions()) {
            vesperaInfo.setText(R.string.perm_requesting);
            requestWifiPermissions(SCAN_PERMISSION_REQUEST_CODE);
            return;
        }
        if (locationManager != null && !locationManager.isLocationEnabled()) {
            vesperaInfo.setText(getString(R.string.location_off, scanPrerequisites()));
            return;
        }
        vesperaInfo.setText(R.string.scanning);
        showSearchingDeviceBar();
        boolean accepted = wifiManager.startScan();
        if (!accepted) {
            vesperaInfo.setText(getString(R.string.scan_throttled, scanPrerequisites()));
            showVesperaScanResult(false);
        }
    }

    private void requestWifiPermissions(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            }, requestCode);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, requestCode);
        }
    }

    private void showVesperaScanResult(boolean freshResults) {
        List<ScanResult> found = new ArrayList<>();
        for (ScanResult result : wifiManager.getScanResults()) {
            if (VesperaDeviceStore.isVesperaSsid(result.SSID)) found.add(result);
        }
        Collections.sort(found, Comparator.comparingInt((ScanResult r) -> r.level).reversed());
        foundDevicesList.removeAllViews();

        ScanResult savedMatch = null;
        for (ScanResult result : found) {
            if (deviceStore.matchesScan(result)) {
                savedMatch = result;
                break;
            }
        }
        savedDeviceOnline = savedMatch != null;

        if (found.isEmpty()) {
            vesperaInfo.setText(getString(
                    freshResults ? R.string.scan_none_fresh : R.string.scan_none_cached,
                    scanPrerequisites()));
            if (isVesperaRequesting()) {
                showConnectingDeviceBar();
            } else if (deviceStore.isConfigured()) {
                showOfflineDeviceBar();
            } else {
                showIdleDeviceBar();
            }
            refreshConnectButtons();
            return;
        }
        vesperaInfo.setText(getString(
                freshResults ? R.string.scan_found_fresh : R.string.scan_found_cached,
                found.size()));

        // Fixed primary bar: prefer saved instrument, else strongest signal.
        ScanResult primary = savedMatch != null ? savedMatch : found.get(0);
        bindDeviceRow(deviceStatusBar, primary, true);

        for (ScanResult result : found) {
            if (result == primary) continue;
            Button pick = new Button(this);
            bindDeviceRow(pick, result, true);
            foundDevicesList.addView(pick);
        }
        if (!savedDeviceOnline && deviceStore.isConfigured()) {
            Button offline = new Button(this);
            offline.setAllCaps(false);
            offline.setEnabled(false);
            offline.setText(getString(R.string.device_offline_row,
                    deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel(),
                    deviceStore.getSsid(),
                    deviceStore.getBssid()));
            styleDeviceRow(offline, COLOR_OFFLINE);
            foundDevicesList.addView(offline);
        }
        if (isVesperaRequesting()) {
            showConnectingDeviceBar();
        }
        refreshConnectButtons();
    }

    private LinearLayout buildScanLegendRow(float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int gap = (int) (6 * density);
        row.setPadding(0, (int) (4 * density), 0, (int) (6 * density));
        row.addView(legendChip(getString(R.string.legend_offline), COLOR_OFFLINE, density, gap));
        row.addView(legendChip(getString(R.string.legend_detected), COLOR_DETECTED, density, gap));
        row.addView(legendChip(getString(R.string.legend_connected), COLOR_CONNECTED, density, 0));
        return row;
    }

    private TextView legendChip(String label, int backgroundColor, float density, int marginEnd) {
        TextView chip = new TextView(this);
        int h = (int) (8 * density);
        int v = (int) (4 * density);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(h, v, h, v);
        chip.setBackgroundColor(backgroundColor);
        chip.setTextColor(backgroundColor == COLOR_DETECTED ? 0xFF212121 : 0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(marginEnd);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void showSearchingDeviceBar() {
        if (deviceStatusBar == null) return;
        if (isVesperaRequesting()) {
            showConnectingDeviceBar();
            return;
        }
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(R.string.scan_bar_searching);
        styleDeviceRow(deviceStatusBar, COLOR_OFFLINE);
    }

    private void showConnectingDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        String model = deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel();
        deviceStatusBar.setText(getString(R.string.scan_bar_connecting, model, deviceStore.getSsid()));
        styleDeviceRow(deviceStatusBar, COLOR_CONNECTING);
    }

    private void showIdleDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(R.string.scan_bar_idle);
        styleDeviceRow(deviceStatusBar, COLOR_OFFLINE);
    }

    private void showOfflineDeviceBar() {
        if (deviceStatusBar == null) return;
        deviceStatusBar.setEnabled(false);
        deviceStatusBar.setOnClickListener(null);
        deviceStatusBar.setText(getString(R.string.device_offline_row,
                deviceStore.getModel().isEmpty() ? "Vespera" : deviceStore.getModel(),
                deviceStore.getSsid(),
                deviceStore.getBssid()));
        styleDeviceRow(deviceStatusBar, COLOR_OFFLINE);
    }

    private void bindDeviceRow(Button row, ScanResult result, boolean clickable) {
        int bars = WifiManager.calculateSignalLevel(result.level, 5) + 1;
        String model = VesperaDeviceStore.guessModel(result.SSID);
        boolean saved = deviceStore.matchesScan(result);
        boolean connected = saved && isVesperaConnected();
        String marker = connected ? "✓ " : (saved ? "● " : "");
        row.setAllCaps(false);
        row.setText(marker + model + "\n" + result.SSID
                + "\n" + result.BSSID + " · " + result.level + " dBm (" + bars + "/5) · "
                + result.frequency + " MHz");
        styleDeviceRow(row, connected ? COLOR_CONNECTED : COLOR_DETECTED);
        if (clickable) {
            row.setEnabled(true);
            row.setOnClickListener(v -> selectScannedDevice(result));
        } else {
            row.setEnabled(false);
            row.setOnClickListener(null);
        }
    }

    private void styleDeviceRow(Button row, int backgroundColor) {
        row.setBackgroundColor(backgroundColor);
        row.setTextColor(backgroundColor == COLOR_DETECTED ? 0xFF212121 : 0xFFFFFFFF);
    }

    private boolean isVesperaConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())
                && VesperaConnectionService.getActiveNetwork() != null;
    }

    private boolean isVesperaRequesting() {
        if (connectRequested) return true;
        String statusCode = VesperaConnectionService.getLastStatus();
        return statusCode != null && statusCode.startsWith(VesperaConnectionService.STATUS_REQUESTING);
    }

    private boolean hasWifiPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception unavailable) {
            show(getString(R.string.location_settings_fail));
        }
    }

    private String scanPrerequisites() {
        boolean locationEnabled = locationManager != null && locationManager.isLocationEnabled();
        return getString(R.string.prereq_summary,
                getString(wifiManager.isWifiEnabled() ? R.string.prereq_wifi_on : R.string.prereq_wifi_off),
                getString(locationEnabled ? R.string.prereq_loc_on : R.string.prereq_loc_off),
                getString(hasWifiPermissions() ? R.string.prereq_perm_ok : R.string.prereq_perm_missing));
    }

    private void connect() {
        if (!hasWifiPermissions()) {
            requestWifiPermissions(LOCATION_REQUEST_CODE);
            return;
        }
        if (!deviceStore.isConfigured()) {
            String ssid = ssidInput.getText().toString().trim();
            String bssid = bssidInput.getText().toString().trim();
            if (VesperaDeviceStore.isVesperaSsid(ssid)
                    && bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
                deviceStore.save(ssid, bssid, deviceStore.getFrequencyMhz());
                refreshConfiguredDeviceLabel();
            } else {
                show(getString(R.string.select_device_first));
                return;
            }
        }
        status.setText(getString(R.string.connecting_to,
                deviceStore.getModel(), deviceStore.getSsid()));
        connectRequested = true;
        showConnectingDeviceBar();
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_CONNECT);
        startForegroundService(service);
        refreshConnectButtons();
    }

    private void disconnect() {
        connectRequested = false;
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_DISCONNECT);
        startService(service);
        setConnectionState(VesperaConnectionService.STATUS_DISCONNECTED);
        clearStaleReachabilityStatus(VesperaConnectionService.STATUS_DISCONNECTED);
    }

    private void clearStaleReachabilityStatus(String connectionStatus) {
        String device = deviceStore.isConfigured() ? (" (" + deviceStore.getSsid() + ")") : "";
        if (VesperaConnectionService.STATUS_LOST.equals(connectionStatus)) {
            show(getString(R.string.stale_lost, device));
        } else {
            show(getString(R.string.stale_disconnected, device));
        }
    }

    private void show(String message) {
        mainHandler.post(() -> status.setText(message));
    }

    private void setConnectionState(String code) {
        mainHandler.post(() -> {
            if (code == null || !code.startsWith(VesperaConnectionService.STATUS_REQUESTING)) {
                connectRequested = false;
            }
            connectionInfo.setText(getString(R.string.connection_state,
                    StatusTexts.connection(this, code)));
            refreshConnectButtons();
            if (deviceStatusBar == null) return;
            if (code != null && code.startsWith(VesperaConnectionService.STATUS_REQUESTING)) {
                showConnectingDeviceBar();
            } else {
                showVesperaScanResult(false);
            }
        });
    }

    /**
     * Like system Wi-Fi: show only Connect or only Disconnect for the current state.
     * Connect (green) when detected and not connected; Disconnect (red) when connected.
     * If offline/not ready, Connect stays visible but disabled (grey).
     */
    private void refreshConnectButtons() {
        if (connect == null || disconnect == null) return;
        boolean connected = isVesperaConnected();
        boolean requesting = isVesperaRequesting();
        boolean canConnect = !connected && !requesting && deviceStore.isConfigured() && savedDeviceOnline;
        if (connected) {
            connect.setVisibility(View.GONE);
            disconnect.setVisibility(View.VISIBLE);
            styleActionButton(disconnect, true, 0xFFC62828);
        } else {
            disconnect.setVisibility(View.GONE);
            connect.setVisibility(View.VISIBLE);
            if (requesting) {
                connect.setText(R.string.btn_connecting);
                styleActionButton(connect, false, COLOR_CONNECTING);
            } else {
                connect.setText(R.string.btn_connect);
                if (canConnect) {
                    styleActionButton(connect, true, COLOR_CONNECTED);
                } else {
                    styleActionButton(connect, false, COLOR_OFFLINE);
                }
            }
        }
    }

    private void styleActionButton(Button button, boolean selectable, int color) {
        button.setEnabled(selectable);
        button.setAllCaps(true);
        button.setBackgroundColor(color);
        button.setTextColor(0xFFFFFFFF);
        button.setAlpha(selectable ? 1f : 0.75f);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != LOCATION_REQUEST_CODE && code != SCAN_PERMISSION_REQUEST_CODE) return;
        if (!hasWifiPermissions()) {
            status.setText(R.string.perm_denied_status);
            vesperaInfo.setText(R.string.perm_denied_scan);
            return;
        }
        if (code == LOCATION_REQUEST_CODE) {
            connect();
        } else {
            refreshVesperaScan();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String currentConnectionStatus = VesperaConnectionService.getLastStatus();
        setConnectionState(currentConnectionStatus);
        if (VesperaConnectionService.STATUS_CONNECTED.equals(currentConnectionStatus)) {
            VesperaConnectionService.requestDaemonRoute(this);
        }
        if (!scanReceiverRegistered) {
            IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(scanResultsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(scanResultsReceiver, filter);
            }
            scanReceiverRegistered = true;
        }
        if (!statusReceiverRegistered) {
            IntentFilter filter = new IntentFilter(VesperaConnectionService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectionStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(connectionStatusReceiver, filter);
            }
            statusReceiverRegistered = true;
        }
        refreshVesperaScan();
    }

    @Override protected void onPause() {
        if (scanReceiverRegistered) {
            unregisterReceiver(scanResultsReceiver);
            scanReceiverRegistered = false;
        }
        if (statusReceiverRegistered) {
            unregisterReceiver(connectionStatusReceiver);
            statusReceiverRegistered = false;
        }
        super.onPause();
    }
}
