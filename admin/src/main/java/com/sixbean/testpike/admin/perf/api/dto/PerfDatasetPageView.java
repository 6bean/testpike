package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Admin-side mirror of the core paged dataset window .
 * <p>Field names match the core record so Jackson maps directly; the bridge
 * never re-clamps, re-sorts or re-paginates — the window arrives authoritative
 * (same posture as PerfScenarioPageView).
 *
 * @param list current page of dataset rows (snake_case view columns)
 * @param total dataset count across all pages of this project
 * @param page 1-based page index actually served (after core clamping)
 * @param pageSize window size actually served (after core clamping)
 */
public record PerfDatasetPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
