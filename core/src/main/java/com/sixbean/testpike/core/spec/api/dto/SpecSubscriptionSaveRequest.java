package com.sixbean.testpike.core.spec.api.dto;

/**
 * Subscription save request (tail; shared by the core internal create/edit endpoints).
 * <p>Edit semantics: blank name/url/checkIntervalMinutes/enabled keep current values;
 * projectId is only honored on create. Set-internal reference fields :
 * sourceKind = url | program_spec (blank on edit keeps the current kind, blank on
 * create defaults to url); providerProjectId/providerSpecName are required only for
 * program_spec rows and ignored for url rows. All validation lives in the service
 * single source.
 */
public record SpecSubscriptionSaveRequest(Long projectId, String name, String url,
                                          Integer checkIntervalMinutes, Boolean enabled,
                                          String sourceKind, Long providerProjectId,
                                          String providerSpecName) {
}
