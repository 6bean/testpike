package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;


public record PlanAssignInput(List<Long> caseIds, Long assigneeId) {
}
