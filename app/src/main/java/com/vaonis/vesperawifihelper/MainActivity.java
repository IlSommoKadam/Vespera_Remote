package com.vaonis.vesperawifihelper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Requests a configured Vespera local-only Wi-Fi without changing the Ethernet default route. */
public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST_CODE = 100;
    private static final int VPN_REQUEST_CODE = 101;
    /** Preferred control endpoints for Singularity/API; FTP 21 is detected but not preferred. */
    private static final int[] PREFERRED_PORTS = {8082, 8083};
    private static final int[] SCAN_PORTS = {
            8082, 8083, 21, 80, 443, 3000, 3001, 5000, 5001, 8000, 8008, 8080, 8443, 8888, 9000, 9090
    };
    private static final int AUTO_DISCOVERY_MAX_ATTEMPTS = 5;
    private static final long AUTO_DISCOVERY_INITIAL_DELAY_MS = 2_000;
    private static final long AUTO_DISCOVERY_RETRY_DELAY_MS = 2_000;

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private LocationManager locationManager;
    private VesperaDeviceStore deviceStore;
    private TextView status;
    private TextView vesperaInfo;
    private TextView connectionInfo;
    private TextView bridgeInfo;
    private TextView configuredDeviceInfo;
    private LinearLayout foundDevicesList;
    private EditText ssidInput;
    private EditText bssidInput;
    private EditText hostInput;
    private EditText portInput;
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService portScanExecutor = Executors.newFixedThreadPool(8);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean portScanInFlight = new AtomicBoolean(false);
    private final AtomicBoolean autoDiscoverySucceeded = new AtomicBoolean(false);
    private Runnable pendingAutoDiscovery;
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
                if (connectionStatus.startsWith("CONNESSO")) {
                    autoDiscoverySucceeded.set(false);
                    scheduleAutoDiscovery(0);
                    ensureSingularityBridge();
                } else if (connectionStatus.startsWith("non connesso")
                        || connectionStatus.startsWith("connessione persa")
                        || connectionStatus.startsWith("non disponibile")) {
                    cancelAutoDiscovery();
                    autoDiscoverySucceeded.set(false);
                    stopSingularityBridge();
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        connectivityManager = getSystemService(ConnectivityManager.class);
        wifiManager = getSystemService(WifiManager.class);
        locationManager = getSystemService(LocationManager.class);
        deviceStore = VesperaDeviceStore.from(this);
        buildUi();
        refreshConfiguredDeviceLabel();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView versionLabel = new TextView(this);
        versionLabel.setText("Vespera Wi-Fi Helper " + appVersionLabel());
        versionLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        status = new TextView(this);
        status.setText("Configura il tuo Vespera (I / II / Pro), poi connetti.");
        connectionInfo = new TextView(this);
        connectionInfo.setText("Stato connessione: non connesso");
        bridgeInfo = new TextView(this);
        bridgeInfo.setText("Bridge Singularity: spento");
        configuredDeviceInfo = new TextView(this);
        vesperaInfo = new TextView(this);
        vesperaInfo.setText("Tocca «Cerca strumenti Vespera» per elencare le reti vicine.");
        foundDevicesList = new LinearLayout(this);
        foundDevicesList.setOrientation(LinearLayout.VERTICAL);

        TextView deviceTitle = new TextView(this);
        deviceTitle.setText("Strumento salvato / da selezionare");
        ssidInput = new EditText(this);
        ssidInput.setHint("SSID (es. vespera2-xxxxxx / VESPERAPRO-xxxxx)");
        ssidInput.setText(deviceStore.getSsid());
        bssidInput = new EditText(this);
        bssidInput.setHint("BSSID MAC (es. 2c:cf:67:xx:xx:xx)");
        bssidInput.setText(deviceStore.getBssid());
        Button saveManual = new Button(this);
        saveManual.setText("Salva SSID/BSSID manuali");
        saveManual.setOnClickListener(v -> saveManualDevice());
        Button clearDevice = new Button(this);
        clearDevice.setText("Cancella strumento salvato");
        clearDevice.setOnClickListener(v -> {
            deviceStore.clear();
            ssidInput.setText("");
            bssidInput.setText("");
            refreshConfiguredDeviceLabel();
            show("Strumento cancellato.");
        });

        Button locationSettings = new Button(this);
        locationSettings.setText("Attiva localizzazione");
        locationSettings.setOnClickListener(v -> openLocationSettings());
        Button refresh = new Button(this);
        refresh.setText("Cerca strumenti Vespera");
        refresh.setOnClickListener(v -> refreshVesperaScan());
        Button connect = new Button(this);
        connect.setText("Connetti Vespera");
        connect.setOnClickListener(v -> connect());
        Button disconnect = new Button(this);
        disconnect.setText("Disconnetti");
        disconnect.setOnClickListener(v -> disconnect());
        hostInput = new EditText(this);
        hostInput.setHint("IP del Vespera (es. 10.0.0.1)");
        hostInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        portInput = new EditText(this);
        portInput.setHint("Porta TCP (es. 8082)");
        portInput.setText("8082");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        Button verify = new Button(this);
        verify.setText("Verifica Wi-Fi / Vespera");
        verify.setOnClickListener(v -> verifyReachability());
        Button findPort = new Button(this);
        findPort.setText("Cerca porta Vespera");
        findPort.setOnClickListener(v -> findVesperaPort(true));

        layout.addView(versionLabel);
        layout.addView(status);
        layout.addView(connectionInfo);
        layout.addView(bridgeInfo);
        layout.addView(deviceTitle);
        layout.addView(configuredDeviceInfo);
        layout.addView(ssidInput);
        layout.addView(bssidInput);
        layout.addView(saveManual);
        layout.addView(clearDevice);
        layout.addView(locationSettings);
        layout.addView(refresh);
        layout.addView(vesperaInfo);
        layout.addView(foundDevicesList);
        layout.addView(connect);
        layout.addView(hostInput);
        layout.addView(portInput);
        layout.addView(verify);
        layout.addView(findPort);
        layout.addView(disconnect);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private String appVersionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "v0.3.0";
        }
    }

    private void refreshConfiguredDeviceLabel() {
        configuredDeviceInfo.setText("Salvato:\n" + deviceStore.describe());
        if (deviceStore.isConfigured()) {
            status.setText("Pronto per " + deviceStore.getModel() + " / " + deviceStore.getSsid());
        }
    }

    private void saveManualDevice() {
        String ssid = ssidInput.getText().toString().trim();
        String bssid = bssidInput.getText().toString().trim();
        if (!VesperaDeviceStore.isVesperaSsid(ssid)) {
            show("SSID non sembra un Vespera (attesi: Vespera-*, vespera2-*, VESPERAPRO-*).");
            return;
        }
        if (!bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
            show("BSSID non valido. Usa il formato aa:bb:cc:dd:ee:ff");
            return;
        }
        int frequency = deviceStore.getFrequencyMhz();
        deviceStore.save(ssid, bssid, frequency);
        refreshConfiguredDeviceLabel();
        show("Salvato " + deviceStore.getModel() + ": " + ssid);
    }

    private void selectScannedDevice(ScanResult result) {
        deviceStore.saveFromScan(result);
        ssidInput.setText(result.SSID);
        bssidInput.setText(result.BSSID == null ? "" : result.BSSID.toLowerCase(Locale.US));
        refreshConfiguredDeviceLabel();
        show("Selezionato " + VesperaDeviceStore.guessModel(result.SSID)
                + " (" + result.SSID + "). Ora puoi Connetti Vespera.");
    }

    /** Asks once for VPN consent, then routes 10.0.0.0/24 to all apps via Vespera Wi-Fi. */
    private void ensureSingularityBridge() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            setBridgeState("autorizza VPN per Singularity…");
            startActivityForResult(prepare, VPN_REQUEST_CODE);
            return;
        }
        startSingularityBridge();
    }

    private void startSingularityBridge() {
        setBridgeState("avvio bridge 10.0.0.0/24…");
        Intent bridge = new Intent(this, VesperaBridgeVpnService.class)
                .setAction(VesperaBridgeVpnService.ACTION_START);
        startForegroundService(bridge);
        mainHandler.postDelayed(
                () -> setBridgeState(VesperaBridgeVpnService.getLastStatus()), 800);
    }

    private void stopSingularityBridge() {
        Intent bridge = new Intent(this, VesperaBridgeVpnService.class)
                .setAction(VesperaBridgeVpnService.ACTION_STOP);
        startService(bridge);
        setBridgeState("bridge spento");
    }

    private void setBridgeState(String message) {
        mainHandler.post(() -> bridgeInfo.setText("Bridge Singularity: " + message));
    }

    /** Lists nearby Vespera I / II / Pro APs for the user to pick. */
    private void refreshVesperaScan() {
        if (!hasWifiPermissions()) {
            vesperaInfo.setText("Concedi Posizione precisa e Dispositivi Wi-Fi nelle vicinanze per cercare Vespera.");
            return;
        }
        vesperaInfo.setText("Scansione Wi-Fi in corso…");
        foundDevicesList.removeAllViews();
        boolean accepted = wifiManager.startScan();
        if (!accepted) {
            vesperaInfo.setText("Android ha limitato/rifiutato la nuova scansione. Mostro l'ultimo risultato. "
                    + scanPrerequisites());
            showVesperaScanResult(false);
        }
    }

    private void showVesperaScanResult(boolean freshResults) {
        List<ScanResult> found = new ArrayList<>();
        for (ScanResult result : wifiManager.getScanResults()) {
            if (VesperaDeviceStore.isVesperaSsid(result.SSID)) found.add(result);
        }
        Collections.sort(found, Comparator.comparingInt((ScanResult r) -> r.level).reversed());
        foundDevicesList.removeAllViews();
        if (found.isEmpty()) {
            vesperaInfo.setText((freshResults ? "Scansione aggiornata. " : "Risultati memorizzati. ")
                    + "Nessun Vespera (I/II/Pro) nelle vicinanze. " + scanPrerequisites()
                    + "\nPuoi comunque inserire SSID/BSSID a mano.");
            return;
        }
        vesperaInfo.setText((freshResults ? "Scansione aggiornata. " : "Ultimo risultato. ")
                + found.size() + " strumento/i trovato/i — tocca per salvare:");
        for (ScanResult result : found) {
            int bars = WifiManager.calculateSignalLevel(result.level, 5) + 1;
            String model = VesperaDeviceStore.guessModel(result.SSID);
            boolean selected = deviceStore.isConfigured()
                    && deviceStore.getSsid().equals(result.SSID)
                    && deviceStore.getBssid().equalsIgnoreCase(result.BSSID);
            Button pick = new Button(this);
            pick.setAllCaps(false);
            pick.setText((selected ? "✓ " : "") + model + "\n" + result.SSID
                    + "\n" + result.BSSID + " · " + result.level + " dBm (" + bars + "/5) · "
                    + result.frequency + " MHz");
            pick.setOnClickListener(v -> selectScannedDevice(result));
            foundDevicesList.addView(pick);
        }
    }

    private boolean hasWifiPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }

    /** A regular Android app cannot enable location itself; it may only bring up this protected setting. */
    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception unavailable) {
            show("Impossibile aprire le impostazioni Posizione su questa ROM.");
        }
    }

    private String scanPrerequisites() {
        boolean locationEnabled = locationManager != null && locationManager.isLocationEnabled();
        return "Wi-Fi=" + (wifiManager.isWifiEnabled() ? "attivo" : "disattivo")
                + ", Localizzazione=" + (locationEnabled ? "attiva" : "DISATTIVA")
                + ", permessi=" + (hasWifiPermissions() ? "OK" : "MANCANTI") + ".";
    }

    /** The foreground service keeps the request alive if Android hides/recreates this Activity. */
    private void connect() {
        if (!hasWifiPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES}, LOCATION_REQUEST_CODE);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST_CODE);
            }
            return;
        }
        if (!deviceStore.isConfigured()) {
            // Prefer form fields if the user typed them but forgot to save.
            String ssid = ssidInput.getText().toString().trim();
            String bssid = bssidInput.getText().toString().trim();
            if (VesperaDeviceStore.isVesperaSsid(ssid)
                    && bssid.matches("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) {
                deviceStore.save(ssid, bssid, deviceStore.getFrequencyMhz());
                refreshConfiguredDeviceLabel();
            } else {
                show("Seleziona o salva prima il tuo Vespera (I / II / Pro).");
                return;
            }
        }
        refreshVesperaScan();
        setConnectionState("richiesta inviata; attendi/accetta il dialogo Android");
        status.setText("Connessione a " + deviceStore.getModel() + " / " + deviceStore.getSsid() + "…");
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_CONNECT);
        startForegroundService(service);
    }

    /** Use this network for Vespera sockets: vesperaNetwork.bindSocket(socket). */
    public Network getVesperaNetwork() { return VesperaConnectionService.getActiveNetwork(); }

    /**
     * Verifies both the link properties and a TCP endpoint through the requested Network.
     * getSocketFactory() ensures this probe never falls back to Ethernet/the default network.
     */
    private void verifyReachability() {
        final Network network = VesperaConnectionService.getActiveNetwork();
        if (network == null) {
            show("Nessuna rete Vespera disponibile: connetti prima.");
            return;
        }
        final LinkProperties properties = connectivityManager.getLinkProperties(network);
        final String link = describeLink(properties);
        final String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            show("Wi-Fi Vespera attiva. " + link + " Inserisci l'IP del Vespera per il test TCP.");
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalidPort) {
            show("Porta TCP non valida.");
            return;
        }
        show("Verifica " + host + ":" + port + " sulla Wi-Fi Vespera…");
        probeExecutor.execute(() -> {
            try (Socket socket = network.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
                show("Vespera raggiungibile su " + host + ":" + port + ". " + link);
            } catch (IOException failure) {
                show("Wi-Fi attiva (" + link + "), ma " + host + ":" + port
                        + " non risponde via rete Vespera: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Tests a small, non-invasive set of common local control ports on the Vespera AP only. */
    private void findVesperaPort(boolean verifyAfterSelect) {
        findVesperaPort(verifyAfterSelect, 0);
    }

    private void findVesperaPort(boolean verifyAfterSelect, int attempt) {
        final Network network = VesperaConnectionService.getActiveNetwork();
        if (network == null) {
            show("Nessuna rete Vespera disponibile: connetti prima.");
            return;
        }
        if (!portScanInFlight.compareAndSet(false, true)) {
            show("Scansione porte già in corso…");
            return;
        }
        final String host = resolveVesperaHost(connectivityManager.getLinkProperties(network));
        mainHandler.post(() -> hostInput.setText(host));
        final int attemptLabel = attempt + 1;
        show("Cerco porte su " + host + " (tentativo " + attemptLabel + "/"
                + AUTO_DISCOVERY_MAX_ATTEMPTS + ")…");
        portScanExecutor.execute(() -> {
            ConcurrentLinkedQueue<Integer> openPorts = new ConcurrentLinkedQueue<>();
            CountDownLatch completed = new CountDownLatch(SCAN_PORTS.length);
            for (int port : SCAN_PORTS) {
                portScanExecutor.execute(() -> {
                    try (Socket socket = network.getSocketFactory().createSocket()) {
                        socket.connect(new InetSocketAddress(host, port), 2_000);
                        openPorts.add(port);
                    } catch (IOException ignored) {
                        // A closed or filtered port is expected; it is not a connection failure.
                    } finally {
                        completed.countDown();
                    }
                });
            }
            try {
                completed.await();
                List<Integer> found = new ArrayList<>(openPorts);
                Collections.sort(found);
                if (found.isEmpty()) {
                    portScanInFlight.set(false);
                    if (attempt + 1 < AUTO_DISCOVERY_MAX_ATTEMPTS) {
                        show("Nessuna porta ancora su " + host
                                + ". Nuovo tentativo tra 2s…");
                        mainHandler.postDelayed(
                                () -> findVesperaPort(verifyAfterSelect, attempt + 1),
                                AUTO_DISCOVERY_RETRY_DELAY_MS);
                    } else {
                        show("Nessuna porta comune su " + host
                                + " dopo " + AUTO_DISCOVERY_MAX_ATTEMPTS
                                + " tentativi. Prova Cerca porta o imposta 8082/21.");
                    }
                    return;
                }
                final int selected = pickPreferredPort(found);
                mainHandler.post(() -> {
                    portInput.setText(String.valueOf(selected));
                    hostInput.setText(host);
                    autoDiscoverySucceeded.set(true);
                    show("Porte aperte su " + host + ": " + found
                            + ". Selezionata " + selected + " (priorità API 8082/8083).");
                    if (verifyAfterSelect) verifyReachability();
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                portScanInFlight.set(false);
            }
        });
    }

    private static int pickPreferredPort(List<Integer> openPorts) {
        for (int preferred : PREFERRED_PORTS) {
            if (openPorts.contains(preferred)) return preferred;
        }
        for (Integer port : openPorts) {
            if (port != 21) return port;
        }
        return openPorts.get(0);
    }

    private String describeLink(LinkProperties properties) {
        if (properties == null) return "proprietà di rete non ancora disponibili.";
        return "interfaccia " + properties.getInterfaceName()
                + ", indirizzi " + properties.getLinkAddresses()
                + ", route " + properties.getRoutes()
                + ", DNS " + properties.getDnsServers();
    }

    private void cancelAutoDiscovery() {
        if (pendingAutoDiscovery != null) {
            mainHandler.removeCallbacks(pendingAutoDiscovery);
            pendingAutoDiscovery = null;
        }
    }

    /** Waits for DHCP/IPv4, then scans ports with retries (services often lag association). */
    private void scheduleAutoDiscovery(int waitAttempt) {
        cancelAutoDiscovery();
        long delay = waitAttempt == 0 ? AUTO_DISCOVERY_INITIAL_DELAY_MS : 750;
        pendingAutoDiscovery = () -> {
            pendingAutoDiscovery = null;
            if (autoDiscoverySucceeded.get()) return;
            Network network = VesperaConnectionService.getActiveNetwork();
            if (network == null) return;
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            if (!hasIpv4(properties)) {
                if (waitAttempt + 1 < 10) {
                    show("Connesso: attendo DHCP/IPv4… (" + (waitAttempt + 1) + ")");
                    scheduleAutoDiscovery(waitAttempt + 1);
                } else {
                    show("Connesso ma senza IPv4 su wlan0. Controlla DHCP Vespera.");
                }
                return;
            }
            String host = resolveVesperaHost(properties);
            hostInput.setText(host);
            show("Connesso (" + host + "): avvio scansione porte automatica…");
            findVesperaPort(true, 0);
        };
        mainHandler.postDelayed(pendingAutoDiscovery, delay);
    }

    private static boolean hasIpv4(LinkProperties properties) {
        if (properties == null) return false;
        for (android.net.LinkAddress address : properties.getLinkAddresses()) {
            String host = address.getAddress().getHostAddress();
            if (host != null && host.contains(".") && !host.startsWith("127.")) return true;
        }
        return false;
    }

    private String resolveVesperaHost(LinkProperties properties) {
        if (properties != null) {
            for (RouteInfo route : properties.getRoutes()) {
                if (route.getGateway() != null && !route.getGateway().isAnyLocalAddress()) {
                    String gateway = route.getGateway().getHostAddress();
                    if (gateway != null && gateway.contains(".")) return gateway;
                }
            }
            for (java.net.InetAddress dns : properties.getDnsServers()) {
                String dnsHost = dns.getHostAddress();
                if (dnsHost != null && dnsHost.contains(".")) return dnsHost;
            }
        }
        String typed = hostInput.getText().toString().trim();
        return typed.isEmpty() ? "10.0.0.1" : typed;
    }

    private void disconnect() {
        cancelAutoDiscovery();
        autoDiscoverySucceeded.set(false);
        stopSingularityBridge();
        Intent service = new Intent(this, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_DISCONNECT);
        startService(service);
        setConnectionState("non connesso");
    }
    private void show(String message) {
        mainHandler.post(() -> status.setText(message));
    }
    private void setConnectionState(String message) {
        mainHandler.post(() -> connectionInfo.setText("Stato connessione: " + message));
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == LOCATION_REQUEST_CODE && hasWifiPermissions()) {
            refreshVesperaScan();
            connect();
        }
        else if (code == LOCATION_REQUEST_CODE) status.setText("Serve la posizione precisa per richiedere la Wi-Fi locale.");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startSingularityBridge();
            } else {
                setBridgeState("VPN rifiutata: Singularity non raggiungerà 10.0.0.1");
                show("Per Singularity serve accettare la VPN bridge.");
            }
        }
    }
    @Override protected void onDestroy() {
        cancelAutoDiscovery();
        probeExecutor.shutdownNow();
        portScanExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        String currentConnectionStatus = VesperaConnectionService.getLastStatus();
        setConnectionState(currentConnectionStatus);
        if (currentConnectionStatus.startsWith("CONNESSO") && !autoDiscoverySucceeded.get()) {
            scheduleAutoDiscovery(0);
        }
        if (currentConnectionStatus.startsWith("CONNESSO") && !VesperaBridgeVpnService.isRunning()) {
            ensureSingularityBridge();
        }
        setBridgeState(VesperaBridgeVpnService.getLastStatus());
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
