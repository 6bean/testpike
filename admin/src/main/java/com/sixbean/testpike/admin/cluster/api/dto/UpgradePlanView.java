package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;


public record UpgradePlanView(
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
