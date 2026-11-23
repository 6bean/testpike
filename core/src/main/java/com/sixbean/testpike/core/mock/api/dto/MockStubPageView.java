package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Paged stub window display-scale iron law for the mock
 * management plane). total counts every rule of the space while the list only
 * carries the current window, kept in evaluation order (priority asc, id asc)
 * so a page shows the same precedence the serving snapshot enforces.
 *
 * @param list current page of stub rules in evaluation order
 * @param total rule count across all pages of this space
 * @param page 1-based page index actually served
 * @param pageSize window size actually served (after clamping)
 */
public record MockStubPageView(
        List<MockStubInfo> list,
        long total,
        int page,
        int pageSize) {
}
