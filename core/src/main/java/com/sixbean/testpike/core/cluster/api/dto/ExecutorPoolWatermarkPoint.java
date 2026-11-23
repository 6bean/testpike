package com.sixbean.testpike.core.cluster.api.dto;

/**
 * One persisted pool-watermark sample for the fleet water-level curve (T294d data plane).
 * <p>Mirrors the demand columns captured each autoscaler cycle: pool identity, the depth
 * waiting to be dispatched, the replica line, executors actually handling work, the p95
 * wait of the claimable backlog, and the server-side sample moment in epoch seconds
 * (stable across the internal → admin → browser envelope).
 */
public record ExecutorPoolWatermarkPoint(
        String executorType,
        int pendingDispatchDepth,
        int currentReplicas,
        int activeExecutors,
        double p95WaitSeconds,
        long sampledEpochSeconds) {
}
