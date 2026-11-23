package com.sixbean.testpike.core.cluster.api.dto;

/**
 * One recorded autoscale action on an executor pool (T294d timeline row).
 * <p>The apply channel writes one row per scale action it actually issues; the read
 * side exposes the newest-first window the fleet page timeline consumes.
 * {@code createdEpochSeconds} carries the event instant as epoch seconds so the
 * envelope stays wire-stable across core and admin without a shared clock type.
 */
public record ExecutorPoolScaleEvent(
        String poolKey,
        String action,
        int desiredReplicas,
        int currentReplicas,
        String reason,
        long createdEpochSeconds) {
}
