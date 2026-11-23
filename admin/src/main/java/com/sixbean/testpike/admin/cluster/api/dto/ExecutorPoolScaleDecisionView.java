package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Bridge view of the core autoscaler verdict for one executor pool. Fields are bound
 * by name from the core envelope; the action vocabulary and the user-facing reason
 * sentence are owned by the core rules source and are never re-declared here (R40) —
 * consumers compare the action string as returned.
 */
public record ExecutorPoolScaleDecisionView(
        String action,
        int desiredReplicas,
        int targetReplicas,
        boolean drainFirst,
        String reason) {
}
