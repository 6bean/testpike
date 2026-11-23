package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Paged scenario window display-scale iron law: admin
 * list endpoints must carry page/pageSize so a space with 200 machines still
 * serves a bounded window). total counts every scenario of the space so the
 * pager stays truthful while the list only carries the current window, in
 * core-owned name order (the editor picklists and the new list page read
 * positions off that order — never re-sorted downstream).
 *
 * @param list current page of scenario declarations
 * @param total scenario count across all pages of this space
 * @param page 1-based page index actually served
 * @param pageSize window size actually served (after clamping)
 */
public record MockScenarioPageView(
        List<MockScenarioInfo> list,
        long total,
        int page,
        int pageSize) {
}
