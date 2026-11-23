package com.sixbean.testpike.core.cluster.api.dto;

import java.util.List;

/**
 *
 * @param precheck per-artifact reachability probe rows for the wizard's
 * step-2 gate (empty when the plan pins no manifest version or the
 * manifest carries no artifacts — the gate never fabricates verdicts)
 */
public record UpgradePlanImpactView(
        String scope,
        List<String> engineCodes,
        List<String> canaryNodeIds,
        int affectedNodes,
        int stepCount,
        int waves,
        int batchSize,
        int cooldownSeconds,
        int estimatedMinutes,
        int activePlans,
        List<ArtifactPrecheckItemView> precheck) {
}
