package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding : null = column untouched;
 * 0 = unbind back to the real environment; otherwise a
 * published own-project space, guarded by the core
 * MockSpaceBindingGate shared with the create chain
 */
public record UpdateCaseRequest(String title, String precondition, String expectedResult,
                                Integer priority, Integer confidence, String endType,
                                Integer phase, List<CaseStepRequest> steps,
                                List<Long> tagIds, Long assigneeId, Integer version,
                                String automationTarget, String appautoEngine,
                                Long appautoEnvProfileId, Long mockSpaceId) {
}
