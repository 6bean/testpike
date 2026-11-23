package com.sixbean.testpike.core.visual.domain.component;


public final class VisualBaselineStatus {

    
    public static final int PENDING = 1;
    
    public static final int CONFIRMED = 2;
    
    public static final int SUPERSEDED = 3;

    private VisualBaselineStatus() {
    }

    
    public static String label(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "待确认";
            case CONFIRMED -> "当前基线";
            case SUPERSEDED -> "已被取代";
            default -> "未知(" + status + ")";
        };
    }
}
