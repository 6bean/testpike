package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Result of comparing the recorded HTTP response bodies of two API test runs
 * (admin mirror of core ResponseCompareView, W-c5). The read path owns the
 * tri-state decision; admin only forwards the view after H1 view-guard checks.
 *
 * @param mode           format tri-state chosen by core
 * @param leftTruncated  true=baseline snapshot exceeded the K8 byte budget —
 *                       the UI must label the diff as covering a truncated body
 * @param rightTruncated same flag for the compared side
 * @param leftSha256     SHA-256 hex of the baseline body (the only comparison
 *                       surface in BINARY mode)
 * @param rightSha256    SHA-256 hex of the compared body
 * @param entries        leaf differences sorted by path (empty in BINARY mode)
 */
public record ResponseCompareView(ResponseCompareMode mode,
                                  boolean leftTruncated,
                                  boolean rightTruncated,
                                  String leftSha256,
                                  String rightSha256,
                                  List<ResponseDiffEntry> entries) {
}
