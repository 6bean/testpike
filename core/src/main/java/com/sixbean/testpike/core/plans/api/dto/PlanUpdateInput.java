package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;


public record PlanUpdateInput(String name, String targetVersion, Long ownerId,
                              List<Long> envIds, CaseScopeRule caseScope,
                              EntryCriteria entryCriteria, ExitCriteria exitCriteria,
                              String planStart, String planEnd, Integer lockMode) {
}
