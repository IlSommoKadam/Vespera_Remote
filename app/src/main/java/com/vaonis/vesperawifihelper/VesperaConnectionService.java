package com.vaonis.vesperawifihelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps Vespera Wi-Fi up on Pi+Ethernet and prefers a <b>system-wide</b> association.
 *
 * Singularity's "Open Wi-Fi settings" screen requires a system-wide Wi-Fi NetworkAgent.
 * Promotion uses an optional root daemon ({@code tools/vespera-netd.sh}) because the
 * app cannot exec {@code su} under SELinux. The daemon also installs an {@code ip rule}
 * so {@code 10.0.0.0/24} goes via {@code wlan0} without a VPN.
 */
public final class VesperaConnectionService extends Service {
    public static final String ACTION_CONNECT = "com.vaonis.vesperawifihelper.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vaonis.vesperawifihelper.DISCONNECT";
    public static final String ACTION_STATUS = "com.vaonis.vesperawifihelper.STATUS";
    public static final String EXTRA_STATUS = "status";

    public static final String STATUS_DISCONNECTED = "DISCONNECTED";
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_LOST = "LOST";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_NEED_CONFIG = "NEED_CONFIG";
    public static final String STATUS_REQUESTING = "REQUESTING";
    public static final String STATUS_ERROR = "ERROR";

    private static final String TAG = "VesperaConn";
    private static final int NOTIFICATION_ID = 42;
    private static final String NET_REQ = "net.req";

    private static volatile Network activeNetwork;
    private static volatile String lastStatus = STATUS_DISCONNECTED;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback holdCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String targetSsid;
    private String targetBssid;

    public static Network getActiveNetwork() { return activeNetwork; }
    public static String getLastStatus() { return lastStatus; }

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        Context localized = AppLocale.wrap(this);
        NotificationChannel channel = new NotificationChannel(
                "vespera_connection",
                localized.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopConnection();
            stopSelf();
            return START_NOT_STICKY;
        }
        Context localized = AppLocale.wrap(this);
        startForeground(NOTIFICATION_ID,
                notification(localized.getString(R.string.conn_notification_connecting)));
        if (holdCallback == null) connect();
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        Context localized = AppLocale.wrap(this);
        return new Notification.Builder(this, "vespera_connection")
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle(localized.getString(R.string.conn_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void connect() {
        VesperaDeviceStore device = VesperaDeviceStore.from(this);
        if (!device.isConfigured()) {
            update(STATUS_NEED_CONFIG);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        targetSsid = device.getSsid();
        targetBssid = device.getBssid();
        update(STATUS_REQUESTING + "|" + device.getModel() + "|" + targetSsid);
        try {
            // Keeps ClientMode from bailing on Ethernet-primary devices.
            holdGenericWifiRequest();
            worker.execute(this::requestDaemonPromote);
            mainHandler.postDelayed(this::findConnectedVespera, 2_000);
            mainHandler.postDelayed(() -> worker.execute(this::requestDaemonPromote), 4_000);
            mainHandler.postDelayed(this::findConnectedVespera, 5_000);
            mainHandler.postDelayed(() -> worker.execute(this::requestDaemonPromote), 8_000);
            mainHandler.postDelayed(this::findConnectedVespera, 9_000);
            mainHandler.postDelayed(this::markUnavailableIfDisconnected, 15_000);
        } catch (RuntimeException failure) {
            update(STATUS_ERROR + "|" + failure.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void holdGenericWifiRequest() {
        if (holdCallback != null) return;
        holdCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (isTargetVesperaWifi(network)) adopt(network);
            }
            @Override public void onLost(Network network) {
                if (network.equals(activeNetwork)) {
                    activeNetwork = null;
                    update(STATUS_LOST);
                }
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (isTargetVesperaWifi(network)) adopt(network);
            }
        };
        NetworkRequest hold = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivity.requestNetwork(hold, holdCallback);
    }

    /** Ask rooted {@code vespera-netd.sh} to connect-network + install wlan route. */
    private void requestDaemonPromote() {
        boolean written = writeNetRequest("promote|" + targetSsid + "|" + targetBssid);
        Log.i(TAG, "daemon promote request written=" + written);
    }

    /** Ask daemon to refresh 10.0.0.0/24 → wlan0 rule (no VPN). */
    public static void requestDaemonRoute(Context context) {
        writeNetRequestStatic(context, "route");
    }

    private boolean writeNetRequest(String line) {
        return writeNetRequestStatic(this, line);
    }

    private static boolean writeNetRequestStatic(Context context, String line) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) return false;
            File req = new File(dir, NET_REQ);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(req, false), StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write('\n');
            }
            // World-readable so the root daemon can consume it.
            //noinspection ResultOfMethodCallIgnored
            req.setReadable(true, false);
            Log.i(TAG, "wrote " + req.getAbsolutePath() + " => " + line);
            return true;
        } catch (Exception failure) {
            Log.w(TAG, "writeNetRequest failed", failure);
            return false;
        }
    }

    private void markUnavailableIfDisconnected() {
        if (!STATUS_CONNECTED.equals(lastStatus)) {
            activeNetwork = null;
            update(STATUS_UNAVAILABLE);
        }
    }

    private void adopt(Network network) {
        activeNetwork = network;
        requestDaemonRoute(this);
        update(STATUS_CONNECTED);
    }

    private boolean isTargetVesperaWifi(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
        WifiInfo info = extractWifiInfo(caps);
        if (info != null) {
            String ssid = sanitizeSsid(info.getSSID());
            String bssid = info.getBSSID();
            if (targetSsid != null && targetSsid.equalsIgnoreCase(ssid)) return true;
            if (targetBssid != null && targetBssid.equalsIgnoreCase(bssid)) return true;
        }
        android.net.LinkProperties lp = connectivity.getLinkProperties(network);
        if (lp != null) {
            for (android.net.LinkAddress addr : lp.getLinkAddresses()) {
                String host = addr.getAddress().getHostAddress();
                if (host != null && host.startsWith("10.0.0.")) return true;
            }
        }
        return false;
    }

    private void findConnectedVespera() {
        for (Network network : connectivity.getAllNetworks()) {
            if (isTargetVesperaWifi(network)) {
                adopt(network);
                return;
            }
        }
    }

    private static WifiInfo extractWifiInfo(NetworkCapabilities caps) {
        if (caps == null) return null;
        android.net.TransportInfo transport = caps.getTransportInfo();
        if (transport instanceof WifiInfo) return (WifiInfo) transport;
        return null;
    }

    private static String sanitizeSsid(String ssid) {
        if (ssid == null) return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private void stopConnection() {
        mainHandler.removeCallbacksAndMessages(null);
        if (holdCallback != null) {
            try { connectivity.unregisterNetworkCallback(holdCallback); } catch (RuntimeException ignored) {}
            holdCallback = null;
        }
        activeNetwork = null;
        update(STATUS_DISCONNECTED);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void update(String status) {
        lastStatus = status;
        Context localized = AppLocale.wrap(this);
        String text = StatusTexts.connection(localized, status);
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        stopConnection();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
