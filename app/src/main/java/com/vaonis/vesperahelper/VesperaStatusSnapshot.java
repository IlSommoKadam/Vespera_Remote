package com.vaonis.vesperahelper;

/** Parsed fields from GET /v1/app/status or /v2/app/status. */
final class VesperaStatusSnapshot {
    final String endpoint;
    final String telescopeId;
    final String model;
    final String state;
    final boolean initialized;
    final String operationType;
    final String observationStatus;
    final String targetName;
    final int stackingCount;
    final long exposureMicroSec;
    final int gain;
    final int batteryPercent;
    final String batteryStatus;
    final String challenge;
    final int bootCount;
    final String tracking;
    final String motors;
    final String step;
    final String coordinates;
    final String firmware;
    final String filter;
    final String temperature;
    final String error;
    final String storage;
    /** Occupied internal storage percent, or -1 if unknown. */
    final int storageUsedPercent;
    final String location;
    final String focus;
    final String rawJson;

    VesperaStatusSnapshot(String endpoint, String telescopeId, String model, String state,
            boolean initialized, String operationType, String observationStatus, String targetName,
            int stackingCount,
            long exposureMicroSec, int gain, int batteryPercent, String batteryStatus,
            String challenge, int bootCount, String tracking, String motors,
            String step, String coordinates, String firmware, String filter, String temperature,
            String error, String storage, int storageUsedPercent, String location, String focus,
            String rawJson) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.telescopeId = telescopeId == null ? "" : telescopeId;
        this.model = model == null ? "" : model;
        this.state = state == null ? "" : state;
        this.initialized = initialized;
        this.operationType = operationType == null ? "" : operationType;
        this.observationStatus = observationStatus == null ? "" : observationStatus;
        this.targetName = targetName == null ? "" : targetName;
        this.stackingCount = stackingCount;
        this.exposureMicroSec = exposureMicroSec;
        this.gain = gain;
        this.batteryPercent = batteryPercent;
        this.batteryStatus = batteryStatus == null ? "" : batteryStatus;
        this.challenge = challenge == null ? "" : challenge;
        this.bootCount = bootCount;
        this.tracking = tracking == null ? "OFF" : tracking;
        this.motors = motors == null ? "" : motors;
        this.step = step == null ? "" : step;
        this.coordinates = coordinates == null ? "" : coordinates;
        this.firmware = firmware == null ? "" : firmware;
        this.filter = filter == null ? "" : filter;
        this.temperature = temperature == null ? "" : temperature;
        this.error = error == null ? "" : error;
        this.storage = storage == null ? "" : storage;
        this.storageUsedPercent = storageUsedPercent;
        this.location = location == null ? "" : location;
        this.focus = focus == null ? "" : focus;
        this.rawJson = rawJson == null ? "" : rawJson;
    }

    boolean hasInstrumentFields() {
        return !telescopeId.isEmpty() || !model.isEmpty() || !state.isEmpty()
                || !operationType.isEmpty() || !targetName.isEmpty() || !challenge.isEmpty()
                || !observationStatus.isEmpty() || !step.isEmpty() || !firmware.isEmpty();
    }

    boolean canSignCommands() {
        return !challenge.isEmpty() && !telescopeId.isEmpty();
    }

    String authMissingCode() {
        if (challenge.isEmpty() && telescopeId.isEmpty()) return "auth_missing";
        if (challenge.isEmpty()) return "auth_missing_challenge";
        return "auth_missing_id";
    }

    /** True when status says the charger / mains is connected. */
    boolean isOnMainsPower() {
        String status = batteryStatus.trim().toUpperCase(java.util.Locale.US);
        if (status.isEmpty()) return false;
        if (isOffMainsPower()) return false;
        return status.contains("CONNECT") || status.contains("CHARG")
                || "AC".equals(status) || "FULL".equals(status) || "PLUGGED".equals(status);
    }

    /** True only when status clearly says the telescope is on battery. */
    boolean isOffMainsPower() {
        String status = batteryStatus.trim().toUpperCase(java.util.Locale.US);
        if (status.isEmpty()) return false;
        return status.contains("DISCONNECT") || status.contains("UNPLUG")
                || "BATTERY".equals(status) || status.contains("DISCHARG")
                || status.contains("NOT_CONNECTED") || status.contains("NOT CONNECTED");
    }

    boolean isObserving() {
        return "RUNNING".equals(observationStatus);
    }

    boolean canResumeObservation() {
        if (isObserving()) return false;
        if ("STOPPED".equals(observationStatus)) return true;
        return VesperaLastTarget.hasTarget();
    }
}
