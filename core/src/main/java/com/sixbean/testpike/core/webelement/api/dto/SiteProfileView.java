package com.sixbean.testpike.core.webelement.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Stored view of one whole-site survey profile. {@code scopePrefix} is always
 * the server-derived segment-aligned value (e.g. "/pr/", "/" for the whole
 * host); {@code ignoreParams} is the effective list after defaults applied.
 */
public record SiteProfileView(
        Long id,
        Long projectId,
        String name,
        String startUrl,
        String scopePrefix,
        List<String> excludeGlobs,
        List<String> ignoreParams,
        boolean verbsGuardEnabled,
        Map<String, Object> budgetOverrides,
        String loginStateName,
        boolean enabled,
        int revision,
        LocalDateTime updatedAt) {

    /** Bean-style alias of the record component, for getter-shaped callers. */
    public Long getId() {
        return id;
    }
}
