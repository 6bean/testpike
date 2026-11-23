package com.sixbean.testpike.core.cases.domain.component;

/**
 * Use case status (state machine implementation refinement with 9 states; database design status column).
 * <p>M1 implements the first 5 states (pending review, under review, approved, needs revision, rejected); execution states 6 to 9 are introduced by later phases (executor/regression).
 *
 */
public enum CaseStatus {

    PENDING_REVIEW(1, "待评审"),
    IN_REVIEW(2, "评审中"),
    APPROVED(3, "已通过"),
    NEED_CHANGE(4, "需修改"),
    REJECTED(5, "已拒绝"),
    EXECUTING(6, "执行中"),
    EXEC_FAILED(7, "执行失败"),
    REGRESSION_FAILED(8, "回归失败"),
    ARCHIVED(9, "已归档");

    private final int code;
    private final String label;

    CaseStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** Parse by status code; return null for invalid values (validated by the caller). */
    public static CaseStatus of(int code) {
        for (CaseStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
