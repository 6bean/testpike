package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Admin bridge mirror of the core ApiRunSetPageView (display-scale iron law:
 * the run-set history door pages). Rows keep the core newest-first order
 * untouched; total covers every ledger of the collection so the drawer's pager
 * stays truthful.
 *
 * @param list     current page of run-set ledgers, newest first
 * @param total    ledger count across all pages of this collection
 * @param page     1-based page index actually served (after core clamping)
 * @param pageSize window size actually served (after core clamping)
 */
public record RunSetPageView(
        List<RunSetView> list,
        long total,
        int page,
        int pageSize) {
}
