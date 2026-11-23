package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 *
 * @param productionScope whether the plan declares production scope (decision
 * E: declared at creation, never environment-guessed —
 * under strict mode a production plan needs a maintenance
 * window plus a second-person review before start; null=false)
 * @param maintenanceWindow declared maintenance window (human-readable range;
 * start-time prerequisite when production + dual review are on)
 */
public record UpgradePlanCreateInput(
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
