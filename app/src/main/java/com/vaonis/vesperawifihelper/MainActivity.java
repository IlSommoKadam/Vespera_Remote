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
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Requests the Vespera's local-only OWE Wi-Fi without changing the Ethernet default route. */
public final class MainActivity extends Activity {
    private static final int LOCATION_REQUEST_CODE = 100;
    private static final int VPN_REQUEST_CODE = 101;
    private static final String SSID = "vespera2-54d802";
    private static final String BSSID = "2c:cf:67:54:d8:02";
    private static final int FREQUENCY_MHZ = 2462;
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
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network vesperaNetwork;
    private TextView status;
    private TextView vesperaInfo;
    private TextView connectionInfo;
    private TextView bridgeInfo;
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
        buildUi();
    }

    private void buildUi() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView versionLabel = new TextView(this);
        versionLabel.setText("Vespera Wi-Fi Helper " + appVersionLabel());
        versionLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        status = new TextView(this);
        status.setText("Pronto: richiesta locale Open per " + SSID);
        connectionInfo = new TextView(this);
        connectionInfo.setText("Stato connessione: non connesso");
        bridgeInfo = new TextView(this);
        bridgeInfo.setText("Bridge Singularity: spento");
        vesperaInfo = new TextView(this);
        vesperaInfo.setText("Ricerca rete Vespera in corso...");
        Button refresh = new Button(this);
        refresh.setText("Aggiorna presenza e segnale");
        refresh.setOnClickListener(v -> refreshVesperaScan());
        Button locationSettings = new Button(this);
        locationSettings.setText("Attiva localizzazione");
        locationSettings.setOnClickListener(v -> openLocationSettings());
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
        layout.addView(vesperaInfo);
        layout.addView(locationSettings);
        layout.addView(refresh);
        layout.addView(connect);
        layout.addView(hostInput);
        layout.addView(portInput);
        layout.addView(verify);
        layout.addView(findPort);
        layout.addView(disconnect);
        setContentView(layout);
    }

    private String appVersionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "v0.2.3";
        }
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

    /** Shows only the exact SSID+BSSID requested by this app, never a similarly named AP. */
    private void refreshVesperaScan() {
        if (!hasWifiPermissions()) {
            vesperaInfo.setText("Concedi Posizione precisa e Dispositivi Wi-Fi nelle vicinanze per cercare Vespera.");
            return;
        }
        // startScan is best-effort. The BroadcastReceiver below waits for the actual result,
        // rather than assuming a scan has finished after an arbitrary delay.
        vesperaInfo.setText("Scansione Wi-Fi in corso…");
        boolean accepted = wifiManager.startScan();
        if (!accepted) {
            vesperaInfo.setText("Android ha limitato/rifiutato la nuova scansione. Mostro l'ultimo risultato disponibile. "
                    + scanPrerequisites());
            showVesperaScanResult(false);
        }
    }

    private void showVesperaScanResult(boolean freshResults) {
        ScanResult vespera = null;
        ScanResult sameSsidDifferentBssid = null;
        for (ScanResult result : wifiManager.getScanResults()) {
            if (SSID.equals(result.SSID) && BSSID.equalsIgnoreCase(result.BSSID)) {
                vespera = result;
                break;
            }
            if (SSID.equals(result.SSID)) sameSsidDifferentBssid = result;
        }
        if (vespera == null) {
            if (sameSsidDifferentBssid != null) {
                vesperaInfo.setText("Vespera trovato, ma BSSID diverso da quello configurato."
                        + "\nSSID: " + sameSsidDifferentBssid.SSID
                        + "\nBSSID rilevato: " + sameSsidDifferentBssid.BSSID
                        + "\nBSSID richiesto: " + BSSID
                        + "\nSegnale: " + sameSsidDifferentBssid.level + " dBm"
                        + "\nNon avvio la connessione: la richiesta resta correttamente vincolata al BSSID esatto.");
                return;
            }
            vesperaInfo.setText((freshResults ? "Scansione aggiornata. " : "Risultati memorizzati. ")
                    + "Vespera non presente: " + SSID + " (" + BSSID + "). " + scanPrerequisites());
            return;
        }
        int bars = WifiManager.calculateSignalLevel(vespera.level, 5) + 1;
        String security = vespera.capabilities.contains("OWE") ? "OWE / Enhanced Open" : vespera.capabilities;
        vesperaInfo.setText((freshResults ? "Scansione aggiornata" : "Ultimo risultato disponibile")
                + "\nVespera trovata\nSSID: " + vespera.SSID
                + "\nBSSID: " + vespera.BSSID
                + "\nSegnale: " + vespera.level + " dBm (" + bars + "/5)"
                + "\nCanale: " + vespera.frequency + " MHz"
                + "\nSicurezza: " + security);
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

    private void legacyConnect() {
        if (!hasWifiPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES}, LOCATION_REQUEST_CODE);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST_CODE);
            }
            return;
        }
        refreshVesperaScan();
        disconnect();
        setConnectionState("richiesta inviata; attendi/accetta il dialogo Android");
        try {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(SSID)
                .setBssid(MacAddress.fromString(BSSID))
                .setIsEnhancedOpen(false)
                .setPreferredChannelsFrequenciesMhz(new int[]{FREQUENCY_MHZ})
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        status.setText("Richiesta di connessione locale inviata…");
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                vesperaNetwork = network;
                setConnectionState("CONNESSO al Vespera (rete locale, senza Internet)");
                show("Connesso a Vespera. Ethernet rimane rete predefinita.");
            }
            @Override public void onLost(Network network) {
                if (network.equals(vesperaNetwork)) vesperaNetwork = null;
                setConnectionState("connessione persa");
                show("Connessione Vespera persa.");
            }
            @Override public void onUnavailable() {
                vesperaNetwork = null;
                setConnectionState("non disponibile / richiesta rifiutata / timeout");
                show("Vespera non disponibile o connessione rifiutata.");
            }
        };
        connectivityManager.requestNetwork(request, networkCallback, 30_000);
        } catch (RuntimeException failure) {
            networkCallback = null;
            setConnectionState("errore: " + failure.getClass().getSimpleName());
            show("Richiesta Wi-Fi non inviata: " + failure.getMessage());
        }
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
        refreshVesperaScan();
        setConnectionState("richiesta inviata; attendi/accetta il dialogo Android");
        status.setText("Richiesta locale avviata dal servizio Vespera.");
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
