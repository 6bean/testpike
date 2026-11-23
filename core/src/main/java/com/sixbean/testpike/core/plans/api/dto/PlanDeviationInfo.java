package com.sixbean.testpike.core.plans.api.dto;


public record PlanDeviationInfo(long id, long planId, long caseId, String caseNo, String caseTitle,
                                int deviationType, String deviationTypeLabel, String reason,
                                long createdBy, String createdByName, String createdAt) {

    
    public static String typeLabel(int type) {
        return switch (type) {
            case 1 -> "计划外用例执行";
            case 2 -> "范围外变更";
            default -> "未知(" + type + ")";
        };
    }
}
