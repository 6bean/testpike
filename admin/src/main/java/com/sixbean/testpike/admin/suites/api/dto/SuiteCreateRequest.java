package com.sixbean.testpike.admin.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declarative mock binding : 0/null = the suite runs
 * against the real environment; otherwise a published
 * own-project space, guarded by the core
 * MockSpaceBindingGate single point
 */
public record SuiteCreateRequest(long projectId, String name, List<Long> caseIds, List<Long> envIds,
                                 List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                                 String automationTarget, String appautoEngine,
                                 Long appautoEnvProfileId, Long mockSpaceId) {
}
