package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

/** Pins a Home-screen shortcut that opens the HD browser, not Helper tabs. */
final class HdHomeShortcut {
    static final String ID = "browse_hd";
    private static final String TAG = "VesperaHdShortcut";
    private static final String PREFS = "vespera_hd_shortcut";
    private static final String KEY_PIN_ASKED = "pin_asked";

    private HdHomeShortcut() {}

    static Intent browseIntent(Context context) {
        return new Intent(context, HdBrowserActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static void pinOnce(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PIN_ASKED, false)) return;
        prefs.edit().putBoolean(KEY_PIN_ASKED, true).apply();
        try {
            ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            for (ShortcutInfo existing : manager.getPinnedShortcuts()) {
                if (ID.equals(existing.getId())) return;
            }
            Intent pin = new Intent(context, HdBrowserActivity.class)
                    .setAction(Intent.ACTION_VIEW);
            ShortcutInfo info = new ShortcutInfo.Builder(context, ID)
                    .setShortLabel(context.getString(R.string.hd_browser_shortcut_short))
                    .setLongLabel(context.getString(R.string.hd_browser_shortcut_label))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_hd_launcher))
                    .setIntent(pin)
                    .build();
            manager.requestPinShortcut(info, null);
        } catch (Exception failure) {
            Log.w(TAG, "pin shortcut failed", failure);
        }
    }
}
