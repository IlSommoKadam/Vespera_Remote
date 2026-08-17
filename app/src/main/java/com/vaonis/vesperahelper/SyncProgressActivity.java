package com.vaonis.vesperahelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * Independent, selectable sync window (own task). Hide keeps the transfer
 * running; Close pauses it until Helper taps Continua.
 */
public final class SyncProgressActivity extends Activity {
    static final String ACTION_FINISH = "com.vaonis.vesperahelper.PHOTO_FINISH_WINDOW";

    private View card;
    private boolean receiverRegistered;
    private boolean closing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(false);
        setTitle(R.string.photo_sync_popup_title);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.18f);
        Context localized = AppLocale.wrap(this);
        float density = getResources().getDisplayMetrics().density;
        card = SyncProgressHud.buildCard(localized, density, this::hideWindow, this::closeAndPause);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        int pad = Math.round(12 * density);
        wrap.setPadding(pad, pad, pad, pad);
        wrap.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(wrap);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        if (SyncProgressHud.latest != null) {
            SyncProgressHud.bindCard(card, localized, SyncProgressHud.latest);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(PhotoSyncService.ACTION_PROGRESS);
        filter.addAction(ACTION_FINISH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(progressReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void hideWindow() {
        closing = true;
        PhotoSyncService.hideWindow(this);
        finish();
    }

    private void closeAndPause() {
        SyncProgress progress = SyncProgressHud.latest;
        if (progress != null && (SyncProgress.PHASE_DONE.equals(progress.phase)
                || SyncProgress.PHASE_ERROR.equals(progress.phase))) {
            hideWindow();
            return;
        }
        closing = true;
        PhotoSyncService.pauseSync(this);
        finish();
    }

    @Override public void onBackPressed() {
        hideWindow();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(progressReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
                if (!isFinishing()) finish();
                return;
            }
            SyncProgress progress = SyncProgressHud.latest;
            if (progress == null || card == null) return;
            SyncProgressHud.bindCard(card, AppLocale.wrap(SyncProgressActivity.this), progress);
            if (closing) return;
            if (SyncProgress.PHASE_PAUSED.equals(progress.phase)) {
                if (!isFinishing()) finish();
            }
        }
    };
}
