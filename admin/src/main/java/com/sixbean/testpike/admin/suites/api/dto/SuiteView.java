package com.sixbean.testpike.admin.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding (; null = the suite runs
 * against the real environment) — mirrors core SuiteInfo
 */
public record SuiteView(long id, long projectId, String name, List<Long> caseIds, List<Long> envIds,
                        List<Long> defaultAssigneeIds, String cronExpr, String lastRunAt,
                        int status, String statusLabel, int caseCount,
                        long createdBy, String createdByName, String createdAt,
                        String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                        Long mockSpaceId) {
}
