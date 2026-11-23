package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Paged scenario window (admin bridge mirror of the core MockScenarioPageView,
 * . Rows arrive in name order from core; total counts every
 * scenario of the space so the pager stays truthful while the window stays
 * bounded (display-scale iron law). The bridge never re-sorts or re-paginates.
 *
 * @param list current page of scenario declarations
 * @param total scenario count across all pages of this space
 * @param page 1-based page index actually served
 * @param pageSize window size actually served (after core clamping)
 */
public record MockScenarioPageView(
        List<MockScenarioView> list,
        long total,
        int page,
        int pageSize) {
}
