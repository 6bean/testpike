package com.sixbean.testpike.core.requirement.domain.component;

import java.util.Map;


public final class TraceLabels {

    private TraceLabels() {
    }

    private static final Map<Integer, String> REQUIREMENT_STATUS = Map.of(
            1, "活跃", 2, "已变更", 3, "已下线");

    private static final Map<Integer, String> EXEC_STATUS = Map.of(
            1, "待派发", 2, "执行中", 3, "成功", 4, "失败", 5, "超时", 6, "已跳过");

    private static final Map<String, String> CASE_DEFECT_STATUS = Map.of(
            "open", "未处理", "in_progress", "处理中", "closed", "已关闭");

    
    public static String requirementStatus(int status) {
        return REQUIREMENT_STATUS.getOrDefault(status, "未知(" + status + ")");
    }

    
    public static String execStatus(int status) {
        return EXEC_STATUS.getOrDefault(status, "未知(" + status + ")");
    }

    
    public static String caseDefectStatus(String defectStatus) {
        if (defectStatus == null) {
            return null;
        }
        return CASE_DEFECT_STATUS.getOrDefault(defectStatus, defectStatus);
    }
}
