package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;

import java.util.List;
import java.util.Map;

/**
 * Shared fixture rig for the site-survey test family: creates a site profile through
 * the single SiteProfileService path and records the id so the owning test can sweep
 * its rows afterwards. Extracted so every survey test calls one creation snippet
 * instead of keeping identical local copies.
 */
final class SiteSurveyProfileFixture {

    private SiteSurveyProfileFixture() {
    }

    /** Create one survey profile with the shared defaults; the id joins the caller's cleanup list. */
    static SiteProfileView createProfile(SiteProfileService siteProfileService, List<Long> createdProfileIds,
                                         long projectId, String name, String startUrl,
                                         Map<String, Object> budgetOverrides) {
        SiteProfileView view = siteProfileService.create(new SiteProfileInput(
                projectId, name, startUrl, List.of("/pr/logout*"), List.of("page", "size"),
                true, budgetOverrides, "默认登录态", true, null));
        createdProfileIds.add(view.getId());
        return view;
    }
}
