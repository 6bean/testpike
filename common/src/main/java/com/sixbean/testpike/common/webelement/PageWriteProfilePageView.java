package com.sixbean.testpike.common.webelement;

import java.util.List;

/**
 * Paged segment of the page write-risk ledger (S-group worst-scale contract):
 * the rows of the current page plus the total the filter matched, so the
 * browser renders honest counts without a second round-trip. One shared
 * shape between core and admin — the same rationale that put
 * {@link SiteSurveyRunView} here instead of copying it per module.
 */
public record PageWriteProfilePageView(
        List<PageWriteProfileView> list,
        long total,
        int page,
        int pageSize) {
}
