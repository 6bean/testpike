package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Paged inspector window over the hit ledger (admin bridge mirror of the core
 * MockHitPageView). total counts rows under the same filter as the window so
 * pager arithmetic stays truthful while new batches keep landing.
 */
public record MockHitPageView(
        List<MockHitView> list,
        long total,
        int page,
        int pageSize) {
}
