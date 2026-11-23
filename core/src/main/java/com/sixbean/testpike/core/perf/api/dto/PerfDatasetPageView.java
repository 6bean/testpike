package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Paged dataset ledger window display-scale iron law: every
 * management-plane list endpoint carries page/pageSize so a project with a
 * hundred datasets still serves a bounded window, same posture as the
 * scenario ledger). total counts every dataset of the project while the list
 * only carries the current window; rows arrive in the asked whitelisted order
 * (or the historical id DESC when no sort is given) and the bridge never
 * re-sorts or re-paginates downstream.
 *
 * @param list current page of dataset rows (snake_case view columns)
 * @param total dataset count across all pages of this project
 * @param page 1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record PerfDatasetPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
