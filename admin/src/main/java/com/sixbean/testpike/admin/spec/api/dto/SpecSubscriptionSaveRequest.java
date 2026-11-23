package com.sixbean.testpike.admin.spec.api.dto;

/**
 * <p>Set-internal reference fields (cross-project contract subscription):
 * sourceKind = url | program_spec (null on create defaults to url, blank on edit keeps
 * the current kind); providerProjectId/providerSpecName are only meaningful for
 * program_spec rows. All validation lives in the core service single source —
 * the admin bridge passes these fields through untouched (no default injection).
 */
public record SpecSubscriptionSaveRequest(Long projectId, String name, String url,
                                          Integer checkIntervalMinutes, Boolean enabled,
                                          String sourceKind, Long providerProjectId,
                                          String providerSpecName) {
}
