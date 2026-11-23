package com.sixbean.testpike.core.repair.domain.component;


public final class RepairOutcome {

    
    public static final int PASSED = 1;
    
    public static final int FAILED = 2;
    
    public static final int SKIPPED = 3;

    private RepairOutcome() {
    }

    
    public static boolean isValid(Integer outcome) {
        return outcome != null && outcome >= PASSED && outcome <= SKIPPED;
    }

    
    public static String label(Integer outcome) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome) {
            case PASSED -> "通过已落新版本";
            case FAILED -> "未过交人工";
            case SKIPPED -> "闸外跳过";
            default -> "未知(" + outcome + ")";
        };
    }
}
