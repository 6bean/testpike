package com.sixbean.testpike.core.plans.api.dto;

/**
 *
 * @param id plan_case ID
 */
public record PlanCaseInfo(long id, long planId, long caseId, String caseNo, String title,
                           Integer phase, Long assigneeId, String assigneeName,
                           int status, String statusLabel, Long batchCaseRef) {

    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "未执行";
            case 2 -> "执行中";
            case 3 -> "通过";
            case 4 -> "失败";
            case 5 -> "跳过";
            default -> "未知";
        };
    }
}
