package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Mirror of one autoscale action row from the core ledger (field-for-field with
 * core ExecutorPoolScaleEvent, so the envelope binds without custom mapping).
 * The fleet timeline renders these newest-first; the reason sentence was written
 * by the apply channel and crosses this bridge unchanged — admin re-states nothing.
 */
public record ExecutorPoolScaleEventView(
        String poolKey,
        String action,
        int desiredReplicas,
        int currentReplicas,
        String reason,
        long createdEpochSeconds) {
}
