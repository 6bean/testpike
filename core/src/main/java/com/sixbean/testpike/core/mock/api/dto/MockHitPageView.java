package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Paged inspector window over the hit ledger .
 * <p>The total counts rows matching the same filter as the window (hits-only /
 * misses-only / all), so the pager arithmetic on the page stays truthful even
 * while new rows keep landing from MQ batches.
 *
 * @param list current page, newest serve-time first
 * @param total rows matching the filter across all pages
 * @param page 1-based page index actually served
 * @param pageSize window size actually served (after clamping)
 */
public record MockHitPageView(List<MockHitInfo> list, long total, int page, int pageSize) {
}
