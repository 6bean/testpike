package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Paged cassette-box window (admin bridge mirror of the core
 * MockRecordingPageView, . Rows arrive in the asked
 * whitelisted order from core; total counts every box of the space so the
 * pager stays truthful while the window stays bounded (display-scale iron
 * law). The bridge never re-sorts or re-paginates.
 *
 * @param list current page of recording boxes
 * @param total box count across all pages of this space
 * @param page 1-based page index actually served (after core clamping)
 * @param pageSize window size actually served (after core clamping)
 */
public record MockRecordingPageView(
        List<MockRecordingView> list,
        long total,
        int page,
        int pageSize) {
}
