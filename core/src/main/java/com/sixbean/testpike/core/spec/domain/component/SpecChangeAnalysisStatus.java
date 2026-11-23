package com.sixbean.testpike.core.spec.domain.component;

/**
 * Change-analysis disposition status codes (single source for
 * tp_api_spec_change_analysis.status): the write-path default and the read-path
 * label both reference this class — literal drift between them is forbidden.
 */
public final class SpecChangeAnalysisStatus {

    /** 0 = pending review: freshly landed analysis row. */
    public static final int PENDING = 0;
    /** 1 = handled: a human reviewed the list (trace only, no state machine touched). */
    public static final int HANDLED = 1;

    private SpecChangeAnalysisStatus() {
    }

    /** Human-readable label for the display side; unknown codes are named, not silenced. */
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
