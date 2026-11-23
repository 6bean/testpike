package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Mirror of one persisted pool-watermark sample from the core history ledger
 * (field-for-field with core ExecutorPoolWatermarkPoint, so the envelope binds
 * without custom mapping). The fleet curve renders these newest-first; every
 * value is a captured observation — admin derives nothing and restates nothing.
 */
public record ExecutorPoolWatermarkView(
        String executorType,
        int pendingDispatchDepth,
        int currentReplicas,
        int activeExecutors,
        double p95WaitSeconds,
        long sampledEpochSeconds) {
}
