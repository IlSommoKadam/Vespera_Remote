package com.vaonis.vesperahelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Builds and refreshes the Foto tab (USB HD + all-day FTP sync). */
final class PhotoPanel {
    private static final int COLOR_OFFLINE = UiStyle.STEEL;
    private static final int COLOR_DETECTED = UiStyle.AMBER;
    private static final int COLOR_CONNECTED = UiStyle.GREEN;
    private static final int COLOR_CONNECTING = UiStyle.SLATE;
    private static final int COLOR_DANGER = UiStyle.ROSE;

    private final Activity activity;
    private final float density;
    private final UsbDiskStore diskStore;
    private final PhotoSyncStore syncStore;
    private final ScrollView scroll;
    private final TextView hdStatus;
    private final TextView savedLabel;
    private final LinearLayout diskList;
    private final Button refreshDisks;
    private final Button mount;
    private final Button unmount;
    private final Button eject;
    private final TextView ejectSafe;
    private final TextView syncWindow;
    private final EditText intervalInput;
    private final Button applyInterval;
    private final TextView syncStatus;
    private final Button syncNow;
    private final Button continueSync;
    private final Button overlayPermission;
    private final TextView overlayHint;
    private final TextView ftpStatus;
    private final TextView ftpHint;
    private boolean receiverRegistered;
    private String selectedId = "";
    private boolean mounted;
    private boolean ejected;
    private int listedCount;
    private String lastMessage = "";

    PhotoPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.diskStore = UsbDiskStore.from(activity);
        this.syncStore = PhotoSyncStore.from(activity);
        this.selectedId = diskStore.getId();

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = title(activity.getString(R.string.photo_section_hd));
        hdStatus = body(activity.getString(R.string.photo_hd_idle));
        savedLabel = body(savedText());

        refreshDisks = action(activity.getString(R.string.photo_btn_refresh_disks), COLOR_CONNECTING);
        refreshDisks.setOnClickListener(v -> activity.startForegroundService(
                new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_LIST_DISKS)));
        mount = action(activity.getString(R.string.photo_btn_mount), COLOR_CONNECTED);
        mount.setOnClickListener(v -> {
            if (selectedId == null || selectedId.isEmpty()) {
                hdStatus.setText(R.string.photo_hd_select_first);
                return;
            }
            activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                    .setAction(PhotoSyncService.ACTION_MOUNT)
                    .putExtra(PhotoSyncService.EXTRA_DISK_ID, selectedId));
        });
        unmount = action(activity.getString(R.string.photo_btn_unmount), COLOR_DANGER);
        unmount.setOnClickListener(v -> activity.startForegroundService(
                new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_UNMOUNT)));
        unmount.setVisibility(View.GONE);
        eject = action(activity.getString(R.string.photo_btn_eject), UiStyle.TERRACOTTA);
        eject.setOnClickListener(v -> activity.startForegroundService(
                new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_EJECT)
                        .putExtra(PhotoSyncService.EXTRA_DISK_ID, selectedId)));
        ejectSafe = body(activity.getString(R.string.photo_hd_safe_to_remove));
        ejectSafe.setTypeface(ejectSafe.getTypeface(), android.graphics.Typeface.BOLD);
        ejectSafe.setTextColor(COLOR_CONNECTED);
        ejectSafe.setTextSize(15);
        ejectSafe.setVisibility(View.GONE);

        diskList = new LinearLayout(activity);
        diskList.setOrientation(LinearLayout.VERTICAL);

        TextView syncTitle = title(activity.getString(R.string.photo_section_sync));
        syncWindow = body(windowText());
        LinearLayout intervalRow = new LinearLayout(activity);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        intervalRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView intervalLabel = body(activity.getString(R.string.photo_sync_interval_label));
        intervalLabel.setPadding(0, 0, (int) (8 * density), 0);
        intervalInput = new EditText(activity);
        intervalInput.setHint(R.string.photo_sync_interval_hint);
        intervalInput.setText(PhotoSyncStore.formatIntervalHours(syncStore.intervalHours()));
        intervalInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        intervalInput.setSingleLine(true);
        intervalInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        intervalInput.setEms(4);
        LinearLayout.LayoutParams intervalLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        intervalInput.setLayoutParams(intervalLp);
        TextView intervalUnit = body(activity.getString(R.string.photo_sync_interval_unit));
        intervalUnit.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        applyInterval = action(activity.getString(R.string.photo_sync_interval_apply), COLOR_CONNECTING);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        applyInterval.setLayoutParams(applyLp);
        applyInterval.setOnClickListener(v -> applyInterval());
        intervalInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInterval();
                return true;
            }
            return false;
        });
        intervalRow.addView(intervalLabel);
        intervalRow.addView(intervalInput);
        intervalRow.addView(intervalUnit);
        intervalRow.addView(applyInterval);
        syncStatus = body(activity.getString(R.string.photo_sync_idle));
        syncNow = action(activity.getString(R.string.photo_btn_sync_now), COLOR_CONNECTED);
        syncNow.setOnClickListener(v -> PhotoSyncService.syncNow(activity));
        continueSync = action(activity.getString(R.string.photo_btn_continue), COLOR_DETECTED);
        continueSync.setOnClickListener(v -> PhotoSyncService.resumeSync(activity));
        continueSync.setVisibility(View.GONE);
        overlayPermission = action(activity.getString(R.string.photo_sync_overlay_btn), COLOR_CONNECTING);
        overlayPermission.setOnClickListener(v -> requestOverlayPermission());
        overlayHint = body(activity.getString(R.string.photo_sync_overlay_hint));
        overlayHint.setTextSize(13);

        TextView ftpTitle = title(activity.getString(R.string.photo_section_ftp));
        ftpStatus = body(activity.getString(R.string.photo_ftp_off));
        ftpHint = body(activity.getString(R.string.photo_ftp_hint));
        ftpHint.setTextSize(13);

        layout.addView(title);
        layout.addView(hdStatus);
        layout.addView(savedLabel);
        layout.addView(refreshDisks);
        layout.addView(mount);
        layout.addView(unmount);
        layout.addView(eject);
        layout.addView(ejectSafe);
        layout.addView(diskList);
        layout.addView(syncTitle);
        layout.addView(syncWindow);
        layout.addView(intervalRow);
        layout.addView(syncStatus);
        layout.addView(syncNow);
        layout.addView(continueSync);
        layout.addView(overlayPermission);
        layout.addView(overlayHint);
        layout.addView(ftpTitle);
        layout.addView(ftpStatus);
        layout.addView(ftpHint);
        scroll.addView(layout);
        scroll.setVisibility(View.GONE);
        refreshMountButtons();
        refreshOverlayRow();
        refreshUserFolderHint();
    }

    void refreshUserFolderHint() {
        ftpHint.setText(activity.getString(R.string.photo_ftp_hint) + "\n\n"
                + activity.getString(R.string.photo_user_folder,
                VesperaPortInventory.userFolderUrl()));
    }

    View view() {
        return scroll;
    }

    void onResume() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(PhotoSyncService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                .setAction(PhotoSyncService.ACTION_LIST_DISKS));
        refreshOverlayRow();
        refreshUserFolderHint();
    }

    void onPause() {
        if (!receiverRegistered) return;
        activity.unregisterReceiver(statusReceiver);
        receiverRegistered = false;
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String hd = intent.getStringExtra(PhotoSyncService.EXTRA_HD);
            String sync = intent.getStringExtra(PhotoSyncService.EXTRA_SYNC);
            String ftp = intent.getStringExtra(PhotoSyncService.EXTRA_FTP);
            String disks = intent.getStringExtra(PhotoSyncService.EXTRA_DISKS);
            String selected = intent.getStringExtra(PhotoSyncService.EXTRA_SELECTED);
            String msg = intent.getStringExtra(PhotoSyncService.EXTRA_MESSAGE);
            mounted = intent.getBooleanExtra(PhotoSyncService.EXTRA_MOUNTED, false);
            ejected = intent.getBooleanExtra(PhotoSyncService.EXTRA_EJECTED, false);
            if (msg != null) lastMessage = msg;
            if (hd != null) hdStatus.setText(hd);
            if (!mounted && lastMessage != null && !lastMessage.isEmpty()) {
                hdStatus.setText(lastMessage);
            }
            if (sync != null) syncStatus.setText(sync);
            String last = intent.getStringExtra(PhotoSyncService.EXTRA_LAST_SYNC);
            if (last != null && !last.isEmpty()
                    && !intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false)) {
                syncStatus.setText(sync == null || sync.isEmpty() ? last : (sync + "\n" + last));
            }
            if (ftp != null) ftpStatus.setText(ftp);
            String hint = intent.getStringExtra(PhotoSyncService.EXTRA_FTP_HINT);
            if (hint != null && !hint.isEmpty()) {
                ftpHint.setText(hint + "\n\n" + activity.getString(R.string.photo_user_folder,
                        VesperaPortInventory.userFolderUrl()));
            }
            if (selected != null && !selected.isEmpty()) selectedId = selected;
            if (disks != null) bindDisks(PhotoSyncService.parseDisks(disks));
            savedLabel.setText(savedText());
            syncWindow.setText(windowText());
            refreshMountButtons();
            boolean paused = intent.getBooleanExtra(PhotoSyncService.EXTRA_PAUSED, false);
            boolean syncing = intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false);
            boolean canContinue = paused || syncStore.inProgress() || syncing;
            continueSync.setVisibility(canContinue ? View.VISIBLE : View.GONE);
            continueSync.setEnabled(canContinue);
            UiStyle.applyRaised(continueSync, canContinue ? COLOR_DETECTED : COLOR_OFFLINE, canContinue);
        }
    };

    private void bindDisks(List<UsbDisk> disks) {
        listedCount = disks.size();
        diskList.removeAllViews();
        if (disks.isEmpty()) {
            TextView empty = body(lastMessage != null && !lastMessage.isEmpty()
                    ? lastMessage : activity.getString(R.string.photo_hd_none));
            diskList.addView(empty);
            return;
        }
        for (UsbDisk disk : disks) {
            Button row = new Button(activity);
            row.setAllCaps(false);
            boolean selected = disk.id().equals(selectedId);
            boolean isMounted = disk.mounted || (mounted && selected);
            String marker = isMounted ? "✓ " : (selected ? "● " : "");
            row.setText(marker + disk.displayTitle()
                    + "\n" + disk.name
                    + (disk.uuid.isEmpty() ? "" : (" · UUID " + disk.uuid))
                    + "\n" + disk.size + " · " + disk.fsType
                    + (isMounted ? (" · " + activity.getString(R.string.photo_hd_row_mounted)) : ""));
            int color = isMounted ? COLOR_CONNECTED : (selected ? COLOR_DETECTED : COLOR_OFFLINE);
            if (isMounted) {
                UiStyle.applyRecessed(row, color);
            } else {
                UiStyle.applyRaised(row, color, true);
            }
            row.setOnClickListener(v -> {
                selectedId = disk.id();
                bindDisks(disks);
                refreshMountButtons();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * density);
            row.setLayoutParams(lp);
            diskList.addView(row);
        }
    }

    private void refreshMountButtons() {
        boolean hasSelection = selectedId != null && !selectedId.isEmpty();
        boolean hasListed = listedCount > 0;
        if (mounted) {
            mount.setVisibility(View.GONE);
            unmount.setVisibility(View.VISIBLE);
        } else {
            unmount.setVisibility(View.GONE);
            mount.setVisibility(View.VISIBLE);
            boolean canMount = hasListed && hasSelection;
            mount.setEnabled(canMount);
            UiStyle.applyRaised(mount, canMount ? COLOR_CONNECTED : COLOR_OFFLINE, canMount);
        }
        boolean showSafe = ejected && !mounted;
        eject.setVisibility(showSafe ? View.GONE : View.VISIBLE);
        ejectSafe.setVisibility(showSafe ? View.VISIBLE : View.GONE);
        if (!showSafe) {
            boolean canEject = mounted || hasListed;
            eject.setEnabled(canEject);
            UiStyle.applyRaised(eject, canEject ? UiStyle.TERRACOTTA : COLOR_OFFLINE, canEject);
        }
    }

    private void refreshOverlayRow() {
        boolean allowed = Settings.canDrawOverlays(activity);
        overlayPermission.setVisibility(allowed ? View.GONE : View.VISIBLE);
        overlayHint.setText(allowed
                ? activity.getString(R.string.photo_sync_overlay_ok)
                : activity.getString(R.string.photo_sync_overlay_hint));
    }

    private void requestOverlayPermission() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void applyInterval() {
        float hours = PhotoSyncStore.parseIntervalHours(intervalInput.getText().toString());
        syncStore.setIntervalHours(hours);
        intervalInput.setText(PhotoSyncStore.formatIntervalHours(hours));
        syncWindow.setText(windowText());
        activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                .setAction(PhotoSyncService.ACTION_LIST_DISKS));
    }

    private String windowText() {
        return activity.getString(R.string.photo_sync_interval_help,
                PhotoSyncStore.formatIntervalHours(syncStore.intervalHours()));
    }

    private String savedText() {
        if (!diskStore.hasSaved()) return activity.getString(R.string.photo_hd_not_saved);
        String label = diskStore.getLabel();
        if (label == null || label.isEmpty() || "-".equals(label)) label = diskStore.getId();
        return activity.getString(R.string.photo_hd_saved, label, diskStore.getId());
    }

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, (int) (10 * density), 0, (int) (4 * density));
        view.setTextColor(0xFF1A237E);
        view.setTextSize(16);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setPadding(0, (int) (2 * density), 0, (int) (6 * density));
        return view;
    }

    private Button action(String text, int color) {
        Button button = new Button(activity);
        button.setAllCaps(true);
        button.setText(text);
        UiStyle.applyRaised(button, color, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * density);
        button.setLayoutParams(lp);
        return button;
    }
}
