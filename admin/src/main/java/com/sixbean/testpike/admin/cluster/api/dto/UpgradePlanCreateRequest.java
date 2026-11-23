package com.sixbean.testpike.admin.cluster.api.dto;

import java.util.List;

/**
 *
 * @param productionScope whether this change targets production (declared, not
 * environment-guessed; drives the decision-E dual-review gate)
 * @param maintenanceWindow declared maintenance window (human-readable range)
 */
public record UpgradePlanCreateRequest(
        String scope,
        List<String> engineCodes,
        List<String> canaryNodeIds,
        Integer batchSize,
        String baselineRef,
        String manifestVersion,
        Boolean productionScope,
        String maintenanceWindow,
        String operator) {
}
