package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * <p>productionScope/maintenanceWindow/reviewedBy/reviewedAt carry the decision-E
 * dual-review surface: the plan declares its production scope and window at
 * creation; the reviewer column is written by a second person through the
 * confirm endpoint and consumed by start (display-only while the key is off).
 */
public record UpgradePlanInfo(
        Long id,
        String scope,
        String targetEngineCodes,
        String strategy,
        String manifestVersion,
        Boolean productionScope,
        String maintenanceWindow,
        String reviewedBy,
        LocalDateTime reviewedAt,
        String status,
        String brakeReason,
        String operator,
        LocalDateTime createdAt) {
}
