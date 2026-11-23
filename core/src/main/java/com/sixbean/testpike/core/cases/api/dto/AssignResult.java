package com.sixbean.testpike.core.cases.api.dto;

/**
 * Single assignment result (02-Interface Design 3.6: assignee and status after assignment, with status unchanged and only the assignee updated).
 *
 * @param caseId Case ID.
 * @param assigneeId Assignee (null=return to pool).
 * @param status Status after assignment (unchanged: 1 pending review / 2 under review).
 */
public record AssignResult(long caseId, Long assigneeId, int status) {
}
