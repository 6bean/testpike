package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record PlanView(long id, long projectId, String name, String targetVersion, int status,
                       String statusLabel, Long ownerId, List<Long> envIds, CaseScopeRuleRequest caseScope,
                       EntryCriteriaRequest entryCriteria, ExitCriteriaRequest exitCriteria,
                       String planStart, String planEnd, long caseCount, long assigneeCount,
                       long createdBy, String createdAt, int lockMode) {
}
