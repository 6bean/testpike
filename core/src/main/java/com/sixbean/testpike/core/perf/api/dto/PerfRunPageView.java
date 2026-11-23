package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Paged run window the workbench run drawer consumes a
 * bounded window instead of every run of the scenario, same S-2 contract
 * posture as the scenario page from . total counts every run of
 * the scenario so the drawer's load-more stays truthful; rows keep the
 * historical newest-first id DESC order and the snake_case view columns, so
 * the row shape never changed on the way to the wire.
 * <p>Sort is exempted here on purpose: the drawer is a headerless div list
 * with no column headers to click — a sort whitelist would be a contract no
 * consumer can reach 's design note, recorded not silently).
 *
 * @param list current page of run rows (core view columns, snake_case)
 * @param total run count across all pages of this scenario
 * @param page 1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record PerfRunPageView(
        List<Map<String, Object>> list,
        long total,
        int page,
        int pageSize) {
}
