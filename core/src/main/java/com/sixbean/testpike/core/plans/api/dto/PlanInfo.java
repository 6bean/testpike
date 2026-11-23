package com.sixbean.testpike.core.plans.api.dto;

import java.util.List;


public record PlanInfo(long id, long projectId, String name, String targetVersion, int status,
                       String statusLabel, Long ownerId, List<Long> envIds, CaseScopeRule caseScope,
                       EntryCriteria entryCriteria, ExitCriteria exitCriteria,
                       String planStart, String planEnd, long caseCount, long assigneeCount,
                       long createdBy, String createdAt, int lockMode) {

    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "草稿";
            case 2 -> "已发布";
            case 3 -> "执行中";
            case 4 -> "已完成";
            case 5 -> "已取消";
            default -> "未知";
        };
    }
}
