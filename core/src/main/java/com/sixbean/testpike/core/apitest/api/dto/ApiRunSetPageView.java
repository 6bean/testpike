package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * Paged run-set history window (display-scale iron law: every management-plane
 * list door carries page/pageSize). Rows arrive newest first (created_at desc,
 * id desc) — the same order the drawer renders — while total counts every run
 * set of the collection so pager arithmetic stays truthful.
 *
 * @param list     current page of run-set ledgers, newest first
 * @param total    ledger count across all pages of this collection
 * @param page     1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record ApiRunSetPageView(
        List<ApiRunSetInfo> list,
        long total,
        int page,
        int pageSize) {
}
