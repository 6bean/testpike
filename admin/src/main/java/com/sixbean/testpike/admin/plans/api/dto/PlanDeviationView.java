package com.sixbean.testpike.admin.plans.api.dto;


public record PlanDeviationView(long id, long planId, long caseId, String caseNo, String caseTitle,
                                int deviationType, String deviationTypeLabel, String reason,
                                long createdBy, String createdByName, String createdAt) {
}
