package com.sixbean.testpike.admin.webelement.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Site profile row as the drawer renders it. {@code scopePrefix} is the
 * server-derived segment-aligned scope ("/pr/", "/" for the whole host) and
 * {@code revision} is echoed back so the next edit can carry it unchanged.
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
}
