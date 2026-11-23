package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record PlanCreateRequest(long projectId, String name, String targetVersion, Long ownerId,
                                List<Long> envIds, CaseScopeRuleRequest caseScope,
                                EntryCriteriaRequest entryCriteria, ExitCriteriaRequest exitCriteria,
                                String planStart, String planEnd, Integer lockMode) {
}
