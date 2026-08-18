package com.vaonis.vesperahelper;

/** Parsed fields from GET /v1/app/status or /v2/app/status (no auth). */
final class VesperaStatusSnapshot {
    final String endpoint;
    final String telescopeId;
    final String model;
    final String state;
    final boolean initialized;
    final String operationType;
    final String targetName;
    final int stackingCount;
    final long exposureMicroSec;
    final int gain;
    final int batteryPercent;
    final String batteryStatus;
    final String challenge;
    final int bootCount;
    final String rawJson;

    VesperaStatusSnapshot(String endpoint, String telescopeId, String model, String state,
            boolean initialized, String operationType, String targetName, int stackingCount,
            long exposureMicroSec, int gain, int batteryPercent, String batteryStatus,
            String challenge, int bootCount, String rawJson) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.telescopeId = telescopeId == null ? "" : telescopeId;
        this.model = model == null ? "" : model;
        this.state = state == null ? "" : state;
        this.initialized = initialized;
        this.operationType = operationType == null ? "" : operationType;
        this.targetName = targetName == null ? "" : targetName;
        this.stackingCount = stackingCount;
        this.exposureMicroSec = exposureMicroSec;
        this.gain = gain;
        this.batteryPercent = batteryPercent;
        this.batteryStatus = batteryStatus == null ? "" : batteryStatus;
        this.challenge = challenge == null ? "" : challenge;
        this.bootCount = bootCount;
        this.rawJson = rawJson == null ? "" : rawJson;
    }

    boolean hasInstrumentFields() {
        return !telescopeId.isEmpty() || !model.isEmpty() || !state.isEmpty()
                || !operationType.isEmpty() || !targetName.isEmpty();
    }

    boolean hasAuthFields() {
        return !challenge.isEmpty() && !telescopeId.isEmpty();
    }

    /** Copy with challenge/bootCount from another snapshot (e.g. Socket.IO). */
    VesperaStatusSnapshot withAuthFrom(VesperaStatusSnapshot other) {
        if (other == null) return this;
        String mergedChallenge = !challenge.isEmpty() ? challenge : other.challenge;
        int mergedBoot = bootCount > 0 ? bootCount : other.bootCount;
        String mergedId = !telescopeId.isEmpty() ? telescopeId : other.telescopeId;
        return new VesperaStatusSnapshot(endpoint, mergedId, model, state, initialized,
                operationType, targetName, stackingCount, exposureMicroSec, gain,
                batteryPercent, batteryStatus, mergedChallenge, mergedBoot, rawJson);
    }
}
