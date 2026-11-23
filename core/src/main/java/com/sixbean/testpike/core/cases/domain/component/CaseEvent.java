package com.sixbean.testpike.core.cases.domain.component;

/**
 * State machine events (complete set of 13 events for state machine implementation refinement).
 * <p>Three review actions (pass/need_change/reject) are registered along with engine-level reserved events (edit_begin/edit_save/restore).
 * Provides integration with edit locks and subsequent misjudgment recovery; transitions for execution-related events are registered in later phases.
 *
 */
public enum CaseEvent {

    PASS("pass", "通过评审"),
    NEED_CHANGE("need_change", "标记需修改"),
    REJECT("reject", "拒绝用例"),
    EDIT_BEGIN("edit_begin", "开始编辑"),
    EDIT_SAVE("edit_save", "保存修改"),
    EXEC_START("exec_start", "启动执行"),
    EXEC_SUCCESS("exec_success", "执行成功"),
    EXEC_FAIL("exec_fail", "执行失败"),
    RETRY_ENV_OK("retry_env_ok", "环境恢复重试"),
    RESTORE("restore", "恢复用例"),
    ARCHIVE("archive", "归档用例"),
    UNARCHIVE("unarchive", "取消归档"),
    DEFECT_FIXED("defect_fixed", "缺陷已修复");

    private final String code;
    private final String label;

    CaseEvent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    /** Human action name (flow guide vocabulary; same enum single source as CaseStatus.label). */
    public String label() {
        return label;
    }
}
