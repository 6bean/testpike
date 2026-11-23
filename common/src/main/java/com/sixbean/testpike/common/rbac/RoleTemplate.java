package com.sixbean.testpike.common.rbac;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;


public enum RoleTemplate {

    
    DEVELOPER("developer", "开发人员",
            EnumSet.of(PermActionFamily.VIEW, PermActionFamily.EDIT)),
    
    PROJECT_MANAGER("project_manager", "项目经理",
            EnumSet.of(PermActionFamily.VIEW, PermActionFamily.EDIT, PermActionFamily.CREATE,
                    PermActionFamily.EXPORT, PermActionFamily.APPROVE)),
    
    TESTER("tester", "测试人员",
            EnumSet.of(PermActionFamily.VIEW, PermActionFamily.EDIT,
                    PermActionFamily.CREATE, PermActionFamily.RUN, PermActionFamily.EXPORT)),
    
    TEST_LEAD("test_lead", "测试负责人",
            EnumSet.of(PermActionFamily.VIEW, PermActionFamily.EDIT, PermActionFamily.CREATE,
                    PermActionFamily.RUN, PermActionFamily.EXPORT, PermActionFamily.APPROVE)),
    
    ADMIN("admin", "系统管理员",
            EnumSet.allOf(PermActionFamily.class));

    private final String code;
    private final String label;
    private final Set<PermActionFamily> families;

    RoleTemplate(String code, String label, Set<PermActionFamily> families) {
        this.code = code;
        this.label = label;
        this.families = Collections.unmodifiableSet(families);
    }

    
    public String code() {
        return code;
    }

    
    public String label() {
        return label;
    }

    
    public Set<PermActionFamily> families() {
        return families;
    }

    
    public Set<String> baselineKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (PermissionCatalog p : PermissionCatalog.values()) {
            if (families.contains(p.family())) {
                keys.add(p.key());
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    
    public static RoleTemplate fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RoleTemplate t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
