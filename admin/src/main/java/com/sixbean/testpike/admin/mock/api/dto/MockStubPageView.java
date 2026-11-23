package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Paged stub window (admin bridge mirror of the core MockStubPageView,
 * . Rows arrive in evaluation order (priority asc, id asc) from core
 * — the same precedence the serving snapshot enforces — and the bridge keeps
 * that order untouched; total covers the whole space so pager arithmetic stays
 * truthful (display-scale iron law).
 *
 * @param list current page of stub rules in evaluation order
 * @param total rule count across all pages of this space
 * @param page 1-based page index actually served
 * @param pageSize window size actually served (after core clamping)
 */
public record MockStubPageView(
        List<MockStubView> list,
        long total,
        int page,
        int pageSize) {
}
