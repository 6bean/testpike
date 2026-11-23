package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Browser write payload for one site profile. The scope prefix is never
 * accepted from the client — core derives it from {@code startUrl}. Null
 * list/boolean/map values fall back to the shipped defaults at the core
 * door; {@code revision} carries the optimistic-lock token on updates.
 */
public record SiteProfileInput(
        Long projectId,
        String name,
        String startUrl,
        List<String> excludeGlobs,
        List<String> ignoreParams,
        Boolean verbsGuardEnabled,
        Map<String, Object> budgetOverrides,
        String loginStateName,
        Boolean enabled,
        Integer revision) {
}
