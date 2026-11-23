package com.sixbean.testpike.admin.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding : null = column untouched;
 * 0 = unbind; otherwise a published own-project space
 * through the same core gate as create
 */
public record SuiteUpdateRequest(String name, List<Long> caseIds, List<Long> envIds,
                                 List<Long> defaultAssigneeIds, String cronExpr, Integer status,
                                 String automationTarget, String appautoEngine,
                                 Long appautoEnvProfileId, Long mockSpaceId) {
}
