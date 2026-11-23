package com.sixbean.testpike.core.suites.api.dto;

import java.util.List;

/**
 *
 * @param mockSpaceId declared mock binding (null = the suite
 * runs against the real environment)
 */
public record SuiteInfo(long id, long projectId, String name, List<Long> caseIds, List<Long> envIds,
                        List<Long> defaultAssigneeIds, String cronExpr, String lastRunAt,
                        int status, String statusLabel, int caseCount,
                        long createdBy, String createdByName, String createdAt,
                        String automationTarget, String appautoEngine, Long appautoEnvProfileId,
                        Long mockSpaceId) {

    
    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "启用";
            case 2 -> "停用";
            default -> "未知";
        };
    }
}
