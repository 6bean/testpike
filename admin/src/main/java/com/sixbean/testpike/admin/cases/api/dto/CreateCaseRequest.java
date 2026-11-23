package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declarative mock binding : 0/null = run against the
 * real environment; otherwise a published space of this
 * project — existence/ownership/published checks are the
 * core MockSpaceBindingGate single point, not re-checked here
 */
public record CreateCaseRequest(long projectId, String title, String precondition, String expectedResult,
                                Integer priority, Integer confidence, String endType,
                                Integer status, Integer phase, List<CaseStepRequest> steps,
                                List<Long> tagIds, Long assigneeId,
                                String automationTarget, String appautoEngine,
                                Long appautoEnvProfileId, Long mockSpaceId) {
}
