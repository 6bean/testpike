package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Paged serving-inventory window for one project (display-scale iron law:
 * a cross-space list at worst-case size must never be a full-table dump).
 * <p>Order = routing order first (space priority, space id), then within-space
 * evaluation order (rule priority, rule id) — the same precedence the traffic
 * plane resolves in, so the page reads like the router sees it.
 *
 * @param list     current window of endpoint rows
 * @param total    endpoint count across all pages of this project
 * @param page     1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record MockServingEndpointPageView(
        List<MockServingEndpointView> list,
        long total,
        int page,
        int pageSize) {
}
