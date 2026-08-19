package com.vaonis.vesperahelper;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shows photo-sync progress in a real, selectable {@link SyncProgressActivity}
 * window (own task). Hide keeps the transfer running; Close pauses it so Helper
 * can resume with Continua.
 */
final class SyncProgressHud {
    static volatile SyncProgress latest;

    private final Service service;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean hiddenByUser;
    private boolean windowShown;
    private long lastBindAt;
    private String lastPhase = "";

    SyncProgressHud(Service service) {
        this.service = service;
    }

    void show() {
        main.post(() -> {
            hiddenByUser = false;
            if (windowShown) return;
            windowShown = true;
            startWindow();
        });
    }

    void hideByUser() {
        main.post(() -> {
            hiddenByUser = true;
            windowShown = false;
            finishWindow();
        });
    }

    void bind(SyncProgress progress) {
        latest = progress;
        main.post(() -> bindLocked(progress));
    }

    void hide() {
        main.post(() -> {
            windowShown = false;
            finishWindow();
        });
    }

    void hideLater(long delayMs) {
        main.postDelayed(() -> {
            if (latest != null && latest.active) return;
            if (latest != null && SyncProgress.PHASE_PAUSED.equals(latest.phase)) return;
            windowShown = false;
            finishWindow();
        }, delayMs);
    }

    private void bindLocked(SyncProgress progress) {
        if (progress == null) return;
        boolean phaseChanged = progress.phase != null && !progress.phase.equals(lastPhase);
        lastPhase = progress.phase == null ? "" : progress.phase;
        long now = SystemClock.elapsedRealtime();
        if (!phaseChanged && now - lastBindAt < 200) return;
        lastBindAt = now;
        boolean show = progress.active
                || SyncProgress.PHASE_DONE.equals(progress.phase)
                || SyncProgress.PHASE_ERROR.equals(progress.phase);
        // Do not startActivity on every byte: that cancels in-flight button taps.
        if (show && !hiddenByUser && !windowShown) {
            windowShown = true;
            startWindow();
        }
        service.sendBroadcast(new Intent(PhotoSyncService.ACTION_PROGRESS)
                .setPackage(service.getPackageName()));
    }

    private void startWindow() {
        if (hiddenByUser) return;
        try {
            service.startActivity(new Intent(service, SyncProgressActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {
        }
    }

    private void finishWindow() {
        try {
            service.sendBroadcast(new Intent(SyncProgressActivity.ACTION_FINISH)
                    .setPackage(service.getPackageName()));
        } catch (Exception ignored) {
        }
    }

    static View buildCard(Context context, float density, Runnable onHide, Runnable onClose) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int radius = Math.round(8 * density);
        int frame = Math.max(3, Math.round(3 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE8EEF4);
        bg.setCornerRadius(radius);
        bg.setStroke(frame, 0xFF1A237E);
        layout.setBackground(bg);
        layout.setElevation(14 * density);
        layout.setClipToOutline(true);

        TextView titleView = new TextView(context);
        titleView.setText(R.string.photo_sync_popup_title);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTag("title");
        int titlePad = Math.round(12 * density);
        titleView.setPadding(titlePad, titlePad, titlePad, titlePad);
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setColor(0xFF1A237E);
        titleView.setBackground(titleBg);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * density);
        body.setPadding(pad, pad, pad, pad);

        TextView phaseView = new TextView(context);
        phaseView.setText(R.string.photo_sync_phase_list);
        phaseView.setTypeface(Typeface.DEFAULT_BOLD);
        phaseView.setTextColor(UiStyle.STEEL_BLUE);
        phaseView.setTextSize(14);
        phaseView.setPadding(0, Math.round(4 * density), 0, Math.round(2 * density));
        phaseView.setTag("phase");

        TextView fileView = new TextView(context);
        fileView.setText(R.string.photo_sync_listing);
        fileView.setTextSize(13);
        fileView.setTextColor(0xFF263238);
        fileView.setTag("file");

        ProgressBar barView = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        barView.setMax(1000);
        barView.setProgress(0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(10 * density));
        barLp.topMargin = Math.round(8 * density);
        barLp.bottomMargin = Math.round(6 * density);
        barView.setLayoutParams(barLp);
        barView.setTag("bar");

        TextView statsView = new TextView(context);
        statsView.setTextSize(12);
        statsView.setTextColor(0xFF455A64);
        statsView.setTag("stats");

        TextView etaView = new TextView(context);
        etaView.setTextSize(13);
        etaView.setTypeface(Typeface.DEFAULT_BOLD);
        etaView.setTextColor(0xFF1A237E);
        etaView.setPadding(0, Math.round(4 * density), 0, Math.round(8 * density));
        etaView.setTag("eta");

        ScrollView summaryScroll = new FixedScrollView(context);
        summaryScroll.setTag("summaryScroll");
        summaryScroll.setFillViewport(true);
        summaryScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(220 * density));
        scrollLp.bottomMargin = Math.round(8 * density);
        summaryScroll.setLayoutParams(scrollLp);
        TextView summaryView = new TextView(context);
        summaryView.setTextSize(12);
        summaryView.setTextColor(0xFF263238);
        summaryView.setTag("summary");
        summaryScroll.addView(summaryView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMarginEnd(Math.round(6 * density));
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMarginStart(Math.round(6 * density));

        Button hide = new Button(context);
        hide.setAllCaps(true);
        hide.setText(R.string.photo_sync_popup_hide);
        hide.setClickable(true);
        hide.setFocusable(true);
        hide.setEnabled(true);
        UiStyle.applyRaised(hide, UiStyle.SLATE, true);
        hide.setLayoutParams(half);
        hide.setOnClickListener(v -> {
            if (onHide != null) onHide.run();
        });

        Button close = new Button(context);
        close.setAllCaps(true);
        close.setText(R.string.photo_sync_popup_close);
        close.setClickable(true);
        close.setFocusable(true);
        close.setEnabled(true);
        UiStyle.applyRaised(close, UiStyle.ROSE, true);
        close.setLayoutParams(half2);
        close.setOnClickListener(v -> {
            if (onClose != null) onClose.run();
        });

        buttons.addView(hide);
        buttons.addView(close);

        body.addView(phaseView);
        body.addView(fileView);
        body.addView(barView);
        body.addView(statsView);
        body.addView(etaView);
        body.addView(summaryScroll);
        body.addView(buttons);

        layout.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    static void bindCard(View card, Context localized, SyncProgress progress) {
        if (card == null || progress == null) return;
        TextView phaseView = card.findViewWithTag("phase");
        TextView fileView = card.findViewWithTag("file");
        TextView statsView = card.findViewWithTag("stats");
        TextView etaView = card.findViewWithTag("eta");
        TextView summaryView = card.findViewWithTag("summary");
        View summaryScroll = card.findViewWithTag("summaryScroll");
        ProgressBar barView = card.findViewWithTag("bar");
        if (phaseView != null) {
            phaseView.setText(SyncProgress.phaseLabel(localized, progress.phase));
            int color = SyncProgress.PHASE_ERROR.equals(progress.phase) ? UiStyle.ROSE
                    : (SyncProgress.PHASE_DONE.equals(progress.phase) ? UiStyle.GREEN
                    : (SyncProgress.PHASE_PAUSED.equals(progress.phase) ? UiStyle.TERRACOTTA : UiStyle.STEEL_BLUE));
            phaseView.setTextColor(color);
        }
        if (fileView != null) {
            if (SyncProgress.PHASE_DONE.equals(progress.phase)) {
                fileView.setText(progress.failed == 0
                        ? localized.getString(R.string.photo_sync_summary_ok)
                        : localized.getString(R.string.photo_sync_summary_partial, progress.failed));
            } else if (SyncProgress.PHASE_LIST.equals(progress.phase)) {
                if (progress.detail != null && !progress.detail.isEmpty()
                        && !localized.getString(R.string.photo_sync_listing).equals(progress.detail)) {
                    fileView.setText(localized.getString(R.string.photo_sync_listing_dir,
                            progress.detail, progress.fileIndex));
                } else {
                    fileView.setText(R.string.photo_sync_listing);
                }
            } else if (progress.fileTotal > 0 && progress.fileName != null && !progress.fileName.isEmpty()) {
                fileView.setText(localized.getString(R.string.photo_sync_file,
                        progress.fileIndex, progress.fileTotal, progress.fileName));
            } else if (progress.detail != null && !progress.detail.isEmpty()) {
                fileView.setText(progress.detail);
            }
        }
        if (statsView != null) {
            statsView.setText(localized.getString(R.string.photo_sync_stats,
                    SyncProgress.formatCount(progress.doneBytes, progress.totalBytes),
                    SyncProgress.formatSpeed(progress.speedBps),
                    progress.copied, progress.skipped, progress.deleted));
        }
        if (etaView != null) {
            boolean finished = SyncProgress.PHASE_DONE.equals(progress.phase)
                    || SyncProgress.PHASE_ERROR.equals(progress.phase)
                    || SyncProgress.PHASE_PAUSED.equals(progress.phase);
            if (SyncProgress.PHASE_DONE.equals(progress.phase)) {
                etaView.setVisibility(View.GONE);
            } else if (finished) {
                etaView.setVisibility(View.VISIBLE);
                etaView.setText(progress.detail == null ? "" : progress.detail);
            } else {
                etaView.setVisibility(View.VISIBLE);
                etaView.setText(localized.getString(R.string.photo_sync_eta,
                        SyncProgress.formatEta(progress.etaMs)));
            }
        }
        if (summaryView != null && summaryScroll != null) {
            boolean showSummary = SyncProgress.PHASE_DONE.equals(progress.phase)
                    && progress.detail != null && !progress.detail.isEmpty();
            summaryScroll.setVisibility(showSummary ? View.VISIBLE : View.GONE);
            if (showSummary) summaryView.setText(progress.detail);
        }
        if (barView != null) {
            barView.setMax(1000);
            barView.setProgress(progress.permille());
        }
    }
}
