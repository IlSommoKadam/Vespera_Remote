package com.vaonis.vesperahelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        Intent ui = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, true);
        try {
            context.startActivity(ui);
        } catch (Exception failure) {
            Log.w(TAG, "MainActivity start failed", failure);
        }

        VesperaDeviceStore device = VesperaDeviceStore.from(context);
        try {
            PhotoSyncService.start(context);
        } catch (Exception failure) {
            Log.w(TAG, "PhotoSyncService start failed", failure);
        }
        if (!device.isConfigured()) {
            Log.i(TAG, "no saved Vespera — UI only");
            return;
        }
        VesperaConnectionService.ensure(context);
        Log.i(TAG, "auto-connect at boot for " + device.getSsid());
    }
}
