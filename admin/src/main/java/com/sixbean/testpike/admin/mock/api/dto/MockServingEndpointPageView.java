package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Paged serving-inventory window (admin bridge mirror of the core record).
 * Routing-first ordering, clamp and the truthful total are core semantics
 * forwarded verbatim.
 *
 * @param list     current window of endpoint rows
 * @param total    endpoint count across all pages of this project
 * @param page     1-based page index actually served
 * @param pageSize window size actually served (after core clamping)
 */
public record MockServingEndpointPageView(
        List<MockServingEndpointView> list,
        long total,
        int page,
        int pageSize) {
}
