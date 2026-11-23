package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Mirror of the core autoscaler demand snapshot for one executor pool (field names and
 * order identical to ExecutorPoolDemand so the RestClient binding maps directly).
 * Read-only view: the fleet page shows these inputs next to the verdict and re-derives
 * nothing on this side.
 */
public record ExecutorPoolDemandView(
        String executorType,
        int pendingDispatchDepth,
        int currentReplicas,
        int activeExecutors,
        double p95WaitSeconds,
        long lastScaleEpochSeconds) {
}
