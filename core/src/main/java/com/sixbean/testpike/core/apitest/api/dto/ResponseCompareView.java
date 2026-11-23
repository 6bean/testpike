package com.sixbean.testpike.core.apitest.api.dto;

import com.sixbean.testpike.core.apitest.domain.component.ResponseCompareMode;
import com.sixbean.testpike.core.apitest.domain.component.ResponseDiffEntry;

import java.util.List;

/**
 * Result of comparing the recorded HTTP response bodies of two API test runs
 * (W-c5). Entries reuse the four-color {@link ResponseDiffEntry} vocabulary —
 * the compare endpoint never invents a second diff language.
 *
 * @param mode          format tri-state chosen by the read path
 * @param leftTruncated true=baseline snapshot exceeded the K8 byte budget, so
 *                      the diff covers a truncated body only (honest flag)
 * @param rightTruncated same flag for the compared side
 * @param leftSha256    SHA-256 hex of the baseline body (always present; the
 *                      only comparison surface in BINARY mode)
 * @param rightSha256   SHA-256 hex of the compared body
 * @param entries       leaf differences sorted by path (empty in BINARY mode)
 */
public record ResponseCompareView(ResponseCompareMode mode,
                                  boolean leftTruncated,
                                  boolean rightTruncated,
                                  String leftSha256,
                                  String rightSha256,
                                  List<ResponseDiffEntry> entries) {
}
