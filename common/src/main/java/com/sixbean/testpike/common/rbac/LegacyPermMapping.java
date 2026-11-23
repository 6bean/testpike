package com.sixbean.testpike.common.rbac;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * user:manage / role:manage / config:manage / llm:config / audit:view
 *
 */
public final class LegacyPermMapping {

    private static final Map<String, String> LEGACY_TO_CATALOG = new HashMap<>();

    static {
        LEGACY_TO_CATALOG.put("user:manage", PermissionCatalog.USER_MANAGE.key());
        LEGACY_TO_CATALOG.put("role:manage", PermissionCatalog.ROLE_MANAGE.key());
        LEGACY_TO_CATALOG.put("config:manage", PermissionCatalog.SYSTEM_CONFIG.key());
        LEGACY_TO_CATALOG.put("llm:config", PermissionCatalog.LLM_CONFIG.key());
        LEGACY_TO_CATALOG.put("audit:view", PermissionCatalog.AUDIT_VIEW.key());
    }

    private LegacyPermMapping() {
    }

    
    public static String toCatalogKey(String legacyKey) {
        return legacyKey == null ? null : LEGACY_TO_CATALOG.get(legacyKey);
    }

    
    public static Set<String> project(Set<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys == null) {
            return out;
        }
        for (String key : keys) {
            String mapped = toCatalogKey(key);
            out.add(mapped != null ? mapped : key);
        }
        return out;
    }

    
    public static Set<String> catalogKeys() {
        return new LinkedHashSet<>(LEGACY_TO_CATALOG.values());
    }
}
