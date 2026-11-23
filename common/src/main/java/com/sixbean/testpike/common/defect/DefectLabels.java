package com.sixbean.testpike.common.defect;


public final class DefectLabels {

    private DefectLabels() {
    }

    
    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "新建";
            case 2 -> "处理中";
            case 3 -> "已修复";
            case 4 -> "验证中";
            case 5 -> "已关闭";
            case 6 -> "重新打开";
            default -> "未知(" + status + ")";
        };
    }

    
    public static String severityLabel(int severity) {
        return switch (severity) {
            case 1 -> "P0";
            case 2 -> "P1";
            case 3 -> "P2";
            case 4 -> "P3";
            default -> String.valueOf(severity);
        };
    }

    
    public static String errorTypeLabel(int errorType) {
        return switch (errorType) {
            case 1 -> "产品缺陷";
            case 2 -> "定位错误";
            case 3 -> "环境问题";
            case 4 -> "依赖失败";
            default -> "未分类";
        };
    }
}
