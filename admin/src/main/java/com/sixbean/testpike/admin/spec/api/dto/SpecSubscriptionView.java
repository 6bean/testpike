package com.sixbean.testpike.admin.spec.api.dto;

/**
 *
 * @param sourceKind pull source kind: url | program_spec (set-internal reference)
 * @param providerProjectId provider project id for program_spec rows (null for url rows)
 * @param providerSpecName provider contract name whose latest version is the pull target
 */
public record SpecSubscriptionView(Long id, Long projectId, String name, String url,
                                   int checkIntervalMinutes, boolean enabled,
                                   Long lastSpecId, String lastStatus, String lastError,
                                   String lastCheckAt, Long createdBy, String createdAt,
                                   String sourceKind, Long providerProjectId,
                                   String providerSpecName) {
}
