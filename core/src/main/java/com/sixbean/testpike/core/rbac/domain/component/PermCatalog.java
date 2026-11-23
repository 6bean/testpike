package com.sixbean.testpike.core.rbac.domain.component;

import com.sixbean.testpike.core.rbac.api.dto.PermInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class PermCatalog {

    
    public static final String CASE_REVIEW = "case:review";
    
    public static final String CASE_ASSIGN = "case:assign";
    
    public static final String CONFIG_MANAGE = "config:manage";
    
    public static final String LLM_CONFIG = "llm:config";
    
    public static final String USER_MANAGE = "user:manage";
    
    public static final String EXECUTOR_VIEW = "executor:view";
    
    public static final String ROLE_MANAGE = "role:manage";
 
    public static final String AUDIT_VIEW = "audit:view";

    private static final Map<String, String> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put(CASE_REVIEW, "用例评审");
        CATALOG.put(CASE_ASSIGN, "用例分配");
        CATALOG.put(CONFIG_MANAGE, "系统配置");
        CATALOG.put(LLM_CONFIG, "大模型配置");
        CATALOG.put(USER_MANAGE, "用户管理");
        CATALOG.put(EXECUTOR_VIEW, "执行器查看");
        CATALOG.put(ROLE_MANAGE, "角色管理");
        CATALOG.put(AUDIT_VIEW, "审计日志");
    }

    private PermCatalog() {
    }

    
    public static List<PermInfo> catalog() {
        return CATALOG.entrySet().stream()
                .map(e -> new PermInfo(e.getKey(), e.getValue()))
                .toList();
    }

    
    public static Set<String> allCodes() {
        return CATALOG.keySet();
    }

    
    public static boolean exists(String code) {
        return code != null && CATALOG.containsKey(code);
    }
}
