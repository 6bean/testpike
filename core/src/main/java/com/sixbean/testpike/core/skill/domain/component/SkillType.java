package com.sixbean.testpike.core.skill.domain.component;


public enum SkillType {

    CASE_CHECK(1, "用例检查"),
    REQUIREMENT_COMPLETE(2, "需求补齐"),
    SCRIPT_DEV_STANDARD(3, "脚本开发规范"),
    TEST_EXEC_STANDARD(4, "测试执行规范"),
    FREE(5, "自由");

    private final int code;
    private final String label;

    SkillType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static SkillType of(int code) {
        for (SkillType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    
    public static String labelOf(int code) {
        SkillType t = of(code);
        return t == null ? String.valueOf(code) : t.label();
    }

    
    public static String allLabels() {
        StringBuilder sb = new StringBuilder();
        for (SkillType t : values()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(t.label());
        }
        return sb.toString();
    }
}
