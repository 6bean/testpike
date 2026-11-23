package com.sixbean.testpike.core.spec.api.dto;

/**
 * Contract subscription row view (tp_spec_subscription shape).
 *
 * @param lastSpecId latest successful pull's spec version row id (null = never succeeded)
 * @param lastStatus updated / unchanged / failed (null = never scanned)
 * @param lastError human-readable failure reason when failed (capped at 500)
 * @param lastCheckAt last scan instant (ViewFormats.formatTs convention)
 * @param sourceKind pull source kind: url | program_spec
 * @param providerProjectId provider project id for program_spec rows (null for url rows)
 * @param providerSpecName provider contract name whose latest version is the pull target
 */
public record SpecSubscriptionInfo(Long id, Long projectId, String name, String url,
                                   int checkIntervalMinutes, boolean enabled,
                                   Long lastSpecId, String lastStatus, String lastError,
                                   String lastCheckAt, Long createdBy, String createdAt,
                                   String sourceKind, Long providerProjectId,
                                   String providerSpecName) {
}
