package com.sixbean.testpike.core.script.domain.component;

/**
 * Single source for refusal messages shared by the script validator family.
 *
 * <p>Every message follows the error-guidance rule: name the offending input,
 * then give the literal fix entry (screen / field / config key) plus a
 * one-line example, so the user can recover without leaving the screen.
 */
public final class ScriptValidationGuidance {

    /** Shown when the action payload cannot be parsed or is not a JSON object. */
    public static final String INVALID_ACTION_JSON =
            "动作 JSON 格式非法，无法保存——请在「用例管理」打开该用例进入脚本编辑页，"
                    + "把「动作 JSON」改成合法 JSON 对象（示例：{\"steps\":[{\"action\":\"click\",\"target\":\"登录按钮\"}]}）后重新保存";

    private ScriptValidationGuidance() {
    }
}
