package com.vaonis.vesperawifihelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.IBinder;

/** Owns the local-only request independently of the Activity lifecycle. */
public final class VesperaConnectionService extends Service {
    public static final String ACTION_CONNECT = "com.vaonis.vesperawifihelper.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vaonis.vesperawifihelper.DISCONNECT";
    public static final String ACTION_STATUS = "com.vaonis.vesperawifihelper.STATUS";
    public static final String EXTRA_STATUS = "status";
    private static final String SSID = "vespera2-54d802";
    private static final String BSSID = "2c:cf:67:54:d8:02";
    private static final int FREQUENCY_MHZ = 2462;
    private static final int NOTIFICATION_ID = 42;
    private static volatile Network activeNetwork;
    private static volatile String lastStatus = "non connesso";

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;

    public static Network getActiveNetwork() { return activeNetwork; }
    public static String getLastStatus() { return lastStatus; }

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        NotificationChannel channel = new NotificationChannel("vespera_connection", "Vespera Wi-Fi", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopConnection();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Connessione Vespera richiesta"));
        if (callback == null) connect();
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, "vespera_connection")
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle("Vespera Wi-Fi Helper")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void connect() {
        update("richiesta inviata; attendi/accetta il dialogo Android");
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                activeNetwork = network;
                update("CONNESSO al Vespera (rete locale, senza Internet)");
            }
            @Override public void onLost(Network network) {
                if (network.equals(activeNetwork)) activeNetwork = null;
                update("connessione persa");
            }
            @Override public void onUnavailable() {
                activeNetwork = null;
                update("non disponibile / richiesta rifiutata / timeout");
            }
        };
        try {
            // Device dumpsys reports this AP as config key "NONE" and TYPE_OPEN.
            // An OWE-only specifier produces the SystemUI "No devices found" dialog.
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(SSID).setBssid(MacAddress.fromString(BSSID))
                    .setIsEnhancedOpen(false)
                    .setPreferredChannelsFrequenciesMhz(new int[]{FREQUENCY_MHZ}).build();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier).build();
            connectivity.requestNetwork(request, callback, 30_000);
        } catch (RuntimeException failure) {
            callback = null;
            update("errore: " + failure.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void stopConnection() {
        if (callback != null) {
            connectivity.unregisterNetworkCallback(callback);
            callback = null;
        }
        activeNetwork = null;
        update("non connesso");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void update(String status) {
        lastStatus = status;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
        Intent update = new Intent(ACTION_STATUS).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
    }

    @Override public void onDestroy() { stopConnection(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
