package com.sixbean.testpike.admin.plans.api.dto;


public record PlanDeviationRequest(long caseId, int deviationType, String reason) {
}
