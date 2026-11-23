package com.sixbean.testpike.core.cases.api.dto;

/**
 * Review request (input parameters for POST /internal/cases/{id}/review; contract in 02-Interface Design 3.1).
 *
 * @param action review action: pass / need_change / reject.
 * @param remark review comment (required for need_change/reject, configurable via review.remark-required).
 * @param version is the optimistic-lock version number, which must equal the current version; otherwise, 40901.
 * @param feedbackTypes feedback issue types (six D1 category codes, multi-select; required for need_change/reject, otherwise 400 with the missing field named).
 * pass may be null; the trailing field means old clients missing the field deserialize to null with zero breakage.
 */
public record ReviewRequest(String action, String remark, int version,
                            java.util.List<String> feedbackTypes) {
}
