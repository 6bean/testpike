package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Paged scenario window (admin bridge mirror of the core PerfScenarioPageView,
 * . Rows arrive in the asked whitelisted order from core;
 * total counts every scenario of the project so the pager stays truthful
 * while the window stays bounded (display-scale iron law). The bridge never
 * re-sorts or re-paginates.
 *
 * @param list current page of scenario rows (core view columns, snake_case)
 * @param total scenario count across all pages of this project
 * @param page 1-based page index actually served (after core clamping)
 * @param pageSize window size actually served (after core clamping)
 */
public record PerfScenarioPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
