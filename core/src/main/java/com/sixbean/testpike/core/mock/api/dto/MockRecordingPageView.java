package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Paged cassette-box window S-2 contract-debt clear: every
 * management-plane list endpoint must carry page/pageSize so a space with 200
 * boxes still serves a bounded window, same posture as the scenario/hit/stub
 * pages). total counts every box of the space so the pager stays truthful
 * while the list only carries the current window; rows arrive in the asked
 * whitelisted order (or the historical id DESC when no sort is given) and the
 * bridge never re-sorts or re-paginates downstream.
 *
 * @param list current page of recording boxes
 * @param total box count across all pages of this space
 * @param page 1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record MockRecordingPageView(
        List<MockRecordingInfo> list,
        long total,
        int page,
        int pageSize) {
}
