package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Paged run window (admin bridge mirror of the core PerfRunPageView,
 * . Rows arrive newest-first from core; total counts every
 * run of the scenario so the drawer's load-more stays truthful while the
 * window stays bounded (display-scale iron law). The bridge never
 * re-paginates. Sort is exempted at core: the drawer is a headerless div
 * list with no column headers to click.
 *
 * @param list current page of run rows (core view columns, snake_case)
 * @param total run count across all pages of this scenario
 * @param page 1-based page index actually served (after core clamping)
 * @param pageSize window size actually served (after core clamping)
 */
public record PerfRunPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
