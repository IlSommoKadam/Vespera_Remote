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
        NotificationChannel channel = new NotificationChannel(
                "vespera_connection", "Vespera Wi-Fi", NotificationManager.IMPORTANCE_LOW);
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
        VesperaDeviceStore device = VesperaDeviceStore.from(this);
        if (!device.isConfigured()) {
            update("configura prima il tuo Vespera (scan → seleziona)");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        final String ssid = device.getSsid();
        final String bssid = device.getBssid();
        final int frequencyMhz = device.getFrequencyMhz();
        update("richiesta inviata per " + device.getModel() + " / " + ssid);
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
            // Open/NONE APs: OWE-only specifier shows SystemUI "No devices found".
            WifiNetworkSpecifier.Builder specifierBuilder = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setBssid(MacAddress.fromString(bssid))
                    .setIsEnhancedOpen(false);
            if (frequencyMhz > 0) {
                specifierBuilder.setPreferredChannelsFrequenciesMhz(new int[]{frequencyMhz});
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifierBuilder.build())
                    .build();
            connectivity.requestNetwork(request, callback, 30_000);
        } catch (RuntimeException failure) {
            callback = null;
            update("errore: " + failure.getClass().getSimpleName() + " — controlla SSID/BSSID");
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
