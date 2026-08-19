package com.vaonis.vesperahelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts VesperaHelper after boot and, if a Vespera is already configured,
 * kicks the connection service so auto-connect does not wait on the UI scan.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "VesperaBoot";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Log.i(TAG, "boot action=" + action);
        SystemSettingsStore settings = SystemSettingsStore.from(context);
        if (!settings.bootStart()) {
            Log.i(TAG, "boot start disabled");
            return;
        }
        Intent ui = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, true);
        try {
            context.startActivity(ui);
            SystemActivityLog.record(context, SystemActivityLog.KIND_BOOT, SystemActivityLog.DETAIL_OK);
        } catch (Exception failure) {
            Log.w(TAG, "MainActivity start failed", failure);
        }

        try {
            PhotoSyncService.start(context);
        } catch (Exception failure) {
            Log.w(TAG, "PhotoSyncService start failed", failure);
        }

        VesperaDeviceStore device = VesperaDeviceStore.from(context);
        if (!settings.wifiConnect() || !device.isConfigured()) {
            if (!device.isConfigured()) Log.i(TAG, "no saved Vespera — UI only");
            else Log.i(TAG, "auto-connect disabled");
            return;
        }
        Intent service = new Intent(context, VesperaConnectionService.class)
                .setAction(VesperaConnectionService.ACTION_CONNECT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.i(TAG, "started connection service for " + device.getSsid());
        } catch (Exception failure) {
            Log.w(TAG, "connection service start failed", failure);
        }
    }
}
