package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Write payload for one whole-site survey profile (tp_site_profile).
 * <p>Null list/boolean/map values fall back to the shipped defaults at the
 * service door; {@code revision} is the optimistic-lock token required by
 * updates (ignored on create). The scope prefix is never accepted from the
 * caller - it is derived from {@code startUrl} server-side.
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
