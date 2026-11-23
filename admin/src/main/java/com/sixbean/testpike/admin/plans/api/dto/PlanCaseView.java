package com.sixbean.testpike.admin.plans.api.dto;

/**
 *
 * @param id plan_case ID
 */
public record PlanCaseView(long id, long planId, long caseId, String caseNo, String title,
                           Integer phase, Long assigneeId, String assigneeName,
                           int status, String statusLabel, Long batchCaseRef) {
}
