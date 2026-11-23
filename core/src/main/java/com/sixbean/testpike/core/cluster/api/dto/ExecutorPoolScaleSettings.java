package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Hot-reloadable guardrail settings for one executor pool (T294c, cluster.* config keys).
 * <p>Every value is read from configuration at call time; nothing here is hard-coded in the
 * decision rules. The per-executor capacity and the P95 wait threshold are also config-backed
 * keys, so the whole input spectrum stays tunable without a rebuild.
 */
public record ExecutorPoolScaleSettings(
        int capacityPerExecutor,
        int minReplicas,
        int maxReplicas,
        int p95WaitThresholdSeconds,
        int cooldownSeconds,
        boolean elasticPaused,
        double budgetMaxUtilization,
        boolean budgetPauseEnabled,
        long nowEpochSeconds) {
}
