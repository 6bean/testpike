package com.sixbean.testpike.core.skill.domain.component;


public enum ProjectSkillRole {

    SCRIPT_DEV(1, "脚本开发"),
    TEST(2, "测试"),
    GENERAL(3, "通用");

 
    public static final ProjectSkillRole DEFAULT = GENERAL;

    private final int code;
    private final String label;

    ProjectSkillRole(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    
    public static ProjectSkillRole of(int code) {
        for (ProjectSkillRole r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return null;
    }

    
    public static String allLabels() {
        StringBuilder sb = new StringBuilder();
        for (ProjectSkillRole r : values()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(r.label());
        }
        return sb.toString();
    }
}
