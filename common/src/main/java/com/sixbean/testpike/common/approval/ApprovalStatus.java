package com.sixbean.testpike.common.approval;


public enum ApprovalStatus {

    DRAFT(1, "草稿"),
    PENDING_APPROVAL(2, "待审批"),
    PUBLISHED(3, "已发布"),
    REJECTED(4, "已拒绝"),
    RETIRED(5, "已下架");

    private final int code;
    private final String label;

    ApprovalStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static ApprovalStatus of(int code) {
        for (ApprovalStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    
    public static String labelOf(int code) {
        ApprovalStatus s = of(code);
        return s == null ? String.valueOf(code) : s.label();
    }
}
