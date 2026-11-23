package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Paged scenario window S-2 contract-debt clear: every
 * management-plane list endpoint must carry page/pageSize so a project with
 * 200 perf scenarios still serves a bounded window, same posture as the
 * recording/hit/stub pages). total counts every scenario of the project so
 * the pager stays truthful while the list only carries the current window;
 * rows arrive in the asked whitelisted order (or the historical id DESC when
 * no sort is given) and the bridge never re-sorts or re-paginates downstream.
 *
 * @param list current page of scenario rows (core view columns, snake_case)
 * @param total scenario count across all pages of this project
 * @param page 1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record PerfScenarioPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
