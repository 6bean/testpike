package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Batch review request input parameters for POST /cases/batch-review.
 * Semantics: performs state-machine validation item by item, skips conflicting items and lists them (non-atomic, unlike batch assignment's all-or-nothing rollback).
 *
 * @param projectId Project ID (isolation: validates that each case belongs to this project and skips cases from other projects)
 * @param action Review action: pass / need_change / reject
 * @param remark Review comment (required for need_change/reject when review.remark-required=true; returns HTTP 400 for the entire request)
 * @param caseIds List of case IDs to review (non-empty, deduplicated, at most 100)
 * @param confirmLowConfidence Secondary confirmation for low confidence (quality gate; the frontend enforces it, while the server validates parameter legitimacy)
 * @param feedbackTypes Feedback issue types (six-category code multi-select; required for need_change/reject, causing a parameter-level HTTP 400 for the entire request)
 * This is structurally consistent with remark; as an appended field, old clients send null with zero breakage.
 */
public record ReviewBatchRequest(Long projectId, String action, String remark,
                                 List<Long> caseIds, Boolean confirmLowConfidence,
                                 List<String> feedbackTypes) {

 /** Legacy five-argument constructor (zero breakage for appended fields: missing type is null; the service layer explicitly returns HTTP 400 for reject/need_change). */
    public ReviewBatchRequest(Long projectId, String action, String remark,
                              List<Long> caseIds, Boolean confirmLowConfidence) {
        this(projectId, action, remark, caseIds, confirmLowConfidence, null);
    }
}
