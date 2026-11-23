package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record PlanAssignRequest(List<Long> caseIds, Long assigneeId) {
}
