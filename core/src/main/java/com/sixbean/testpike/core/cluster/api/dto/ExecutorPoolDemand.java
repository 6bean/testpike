package com.sixbean.testpike.core.cluster.api.dto;

/**
 * One autoscaler input snapshot for a single executor pool (T294c input spectrum).
 * <p>Fields: pool identity, the depth still waiting to be dispatched, the replica line the
 * fleet is running now, how many executors are actually handling work, the P95 seconds a
 * pending dispatch has been waiting, and the epoch seconds of the last scale event
 * (0 when the pool has never scaled).
 */
public record ExecutorPoolDemand(
        String executorType,
        int pendingDispatchDepth,
        int currentReplicas,
        int activeExecutors,
        double p95WaitSeconds,
        long lastScaleEpochSeconds) {
}
