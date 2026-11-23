package com.sixbean.testpike.core.document.domain.component;


public final class DocChangeAnalysisStatus {

    
    public static final int PENDING = 0;
    
    public static final int HANDLED = 1;

    private DocChangeAnalysisStatus() {
    }

    
    public static String label(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "待复核";
            case HANDLED -> "已处置";
            default -> "未知(" + status + ")";
        };
    }
}
