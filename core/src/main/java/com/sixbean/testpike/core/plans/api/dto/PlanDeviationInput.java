package com.sixbean.testpike.core.plans.api.dto;


public record PlanDeviationInput(long caseId, int deviationType, String reason) {
}
