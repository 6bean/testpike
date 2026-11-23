package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Shared label mapping for execution facts shown to the model (AM batch
 * ruling ③): the repair-diagnosis block ([E]) and the pinned-case failure
 * summary ([R1b]) are two consumers of the SAME vocabulary, so the enum→
 * display tables live here once instead of being copied per consumer
 * (single-source rule — a diverging wording would make the model see one
 * status under two names inside a single prompt).
 * <p>Values mirror the core DDL domains: exec status 1-6, AI attribution
 * 1-3 (null is handled by the callers as "not classified"). Unknown codes
 */
public final class ExecutionLabels {

    private ExecutionLabels() {
    }

    
    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "待派发";
            case 2 -> "执行中";
            case 3 -> "成功";
            case 4 -> "失败";
            case 5 -> "超时";
            case 6 -> "已跳过";
            default -> "未知(" + status + ")";
        };
    }

    
    public static String classLabel(int aiFailureClass) {
        return switch (aiFailureClass) {
            case 1 -> "产品缺陷";
            case 2 -> "AI定位错误";
            case 3 -> "环境问题";
            default -> "未知(" + aiFailureClass + ")";
        };
    }
}
