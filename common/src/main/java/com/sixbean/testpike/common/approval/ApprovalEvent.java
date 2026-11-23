package com.sixbean.testpike.common.approval;


public enum ApprovalEvent {

    SUBMIT("submit", "提交审批"),
    APPROVE("approve", "审批通过"),
    REJECT("reject", "审批拒绝"),
    RETIRE("retire", "下架");

    private final String code;
    private final String label;

    ApprovalEvent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
