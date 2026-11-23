package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Space lifecycle transition intake .
 * <p>Only the two numbers core's internal transition endpoint needs: the target
 * lifecycle state and the optimistic-lock version the caller last saw. Which
 * edges are legal (draft→publish, publish→retire), the publish-time
 * production-environment re-check and the version conflict verdict all live in
 * core MockSpaceService.transition — the bridge forwards and never re-decides
 * (save-chain single point, red line 6).
 *
 * @param targetStatus 1 publish / 2 retire (0 back-to-draft is not a legal
 * edge; core answers the human-worded refusal)
 * @param expectedVersion optimistic-lock version from the row the operator is
 * looking at (UI passes the listed row's version through)
 */
public record MockSpaceTransitionRequest(Integer targetStatus, Integer expectedVersion) {
}
