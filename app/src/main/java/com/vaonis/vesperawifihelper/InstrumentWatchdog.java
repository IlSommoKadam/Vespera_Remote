package com.vaonis.vesperawifihelper;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls whether Singularity sees the Vespera instrument every {@value #INTERVAL_MS}
 * ms while Wi‑Fi Helper is in the foreground. On failure, refreshes routing and
 * restarts Singularity before rechecking.
 */
final class InstrumentWatchdog {
    private static final String TAG = "InstrumentWatchdog";
    static final String SINGULARITY_PACKAGE = "com.vaonis.barnard";
    static final String ACTION_STATUS = "com.vaonis.vesperawifihelper.INSTRUMENT_STATUS";
    static final String EXTRA_DETECTED = "detected";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_MANUAL = "manual";

    static final long INTERVAL_MS = 5_000;
    private static final int[] API_PORTS = {8083, 8082};
    private static final int PROBE_TIMEOUT_MS = 1_500;
    private static final long RECHECK_AFTER_RESTART_MS = 5_000;
    private static final long RESTART_COOLDOWN_MS = 30_000;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean(false);
    private volatile long lastSingularityRestartMs;

    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            runCheck(false);
            if (running.get()) {
                mainHandler.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    InstrumentWatchdog(Context context) {
        this.appContext = context.getApplicationContext();
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        mainHandler.removeCallbacks(periodicCheck);
        mainHandler.post(periodicCheck);
    }

    void stop() {
        running.set(false);
        mainHandler.removeCallbacks(periodicCheck);
        checkInFlight.set(false);
        recoveryInFlight.set(false);
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    void requestManualCheck() {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, appContext.getString(R.string.watchdog_not_connected), true);
            return;
        }
        runCheck(true);
    }

    private void runCheck(boolean manual) {
        if (!running.get() && !manual) return;
        if (!checkInFlight.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                performCheck(manual);
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    private void performCheck(boolean manual) {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, appContext.getString(R.string.watchdog_not_connected), manual);
            return;
        }

        SingularityDetector.Result result = SingularityDetector.check(appContext);
        if (result.isConnected()) {
            emit(true, -1, appContext.getString(R.string.watchdog_singularity_detected), manual);
            return;
        }

        emit(false, -1, messageFor(result), manual);
        if (shouldRecover(result) && recoveryInFlight.compareAndSet(false, true)) {
            try {
                recoverFromFailure(manual);
            } finally {
                recoveryInFlight.set(false);
            }
        }
    }

    private String messageFor(SingularityDetector.Result result) {
        switch (result.status) {
            case NOT_RUNNING:
                return appContext.getString(R.string.watchdog_singularity_not_running);
            case API_DOWN:
                return appContext.getString(R.string.watchdog_singularity_api_down);
            case NO_WIFI:
                return appContext.getString(R.string.watchdog_singularity_no_wifi);
            case DAEMON_MISSING:
                return appContext.getString(R.string.watchdog_singularity_no_daemon);
            case DISCONNECTED:
            default:
                return appContext.getString(R.string.watchdog_singularity_not_detected);
        }
    }

    private boolean shouldRecover(SingularityDetector.Result result) {
        return result.status == SingularityDetector.Status.DISCONNECTED
                || result.status == SingularityDetector.Status.NOT_RUNNING
                || result.status == SingularityDetector.Status.API_DOWN;
    }

    private void recoverFromFailure(boolean manual) {
        long now = System.currentTimeMillis();
        boolean canRestartSingularity = now - lastSingularityRestartMs >= RESTART_COOLDOWN_MS;
        emit(false, -1, appContext.getString(
                canRestartSingularity
                        ? R.string.watchdog_recovering
                        : R.string.watchdog_recovering_route_only), manual);

        VesperaConnectionService.requestDaemonRoute(appContext);
        VesperaConnectionService.refreshConnectedNetwork(appContext);
        sleepQuietly(1_500);

        SingularityDetector.Result routeResult = SingularityDetector.check(appContext);
        if (routeResult.isConnected()) {
            emit(true, -1, appContext.getString(R.string.watchdog_recovered_route), manual);
            return;
        }

        if (!canRestartSingularity) {
            emit(false, -1, appContext.getString(R.string.watchdog_restart_cooldown), manual);
            return;
        }

        lastSingularityRestartMs = now;
        VesperaConnectionService.requestSingularityRestart(appContext);
        launchSingularity();
        sleepQuietly(RECHECK_AFTER_RESTART_MS);

        SingularityDetector.Result restartResult = SingularityDetector.check(appContext);
        if (restartResult.isConnected()) {
            emit(true, -1, appContext.getString(R.string.watchdog_recovered_restart), manual);
        } else {
            emit(false, -1, appContext.getString(R.string.watchdog_restart_failed), manual);
        }
    }

    /** TCP probe used only for Wi‑Fi Helper port discovery, not Singularity detection. */
    static int probeApiPort(Network network) {
        if (network == null) return -1;
        for (int candidate : API_PORTS) {
            try (Socket socket = network.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("10.0.0.1", candidate), PROBE_TIMEOUT_MS);
                return candidate;
            } catch (IOException ignored) {
                // Try the other known Vespera API port.
            }
        }
        return -1;
    }

    private void launchSingularity() {
        Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(SINGULARITY_PACKAGE);
        if (launch == null) {
            Log.w(TAG, "Singularity launch intent unavailable");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            appContext.startActivity(launch);
        } catch (Exception failure) {
            Log.w(TAG, "Singularity launch failed", failure);
        }
    }

    private void emit(boolean detected, int port, String message, boolean manual) {
        Context localized = AppLocale.wrap(appContext);
        String localizedMessage = message;
        if (manual) {
            localizedMessage = localized.getString(R.string.watchdog_manual_prefix, message);
        }
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(appContext.getPackageName())
                .putExtra(EXTRA_DETECTED, detected)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_MESSAGE, localizedMessage)
                .putExtra(EXTRA_MANUAL, manual);
        appContext.sendBroadcast(intent);
        Log.i(TAG, (detected ? "singularity-detected" : "singularity-missing")
                + " manual=" + manual + " msg=" + localizedMessage);
    }

    private static boolean STATUS_CONNECTED() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
