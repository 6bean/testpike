package com.sixbean.testpike.admin.cluster.api.dto;

import java.util.List;


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
