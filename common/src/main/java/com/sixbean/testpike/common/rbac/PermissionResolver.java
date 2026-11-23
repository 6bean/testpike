package com.sixbean.testpike.common.rbac;

import com.sixbean.testpike.common.exception.ValidationException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


public final class PermissionResolver {

    
    private static final String KEY_PREFIX = "perm:";

    private PermissionResolver() {
    }

    
    public static Set<String> resolve(RoleTemplate template, Set<String> allowOverrides,
                                      Set<String> denyOverrides) {
        return resolve(template.baselineKeys(), allowOverrides, denyOverrides);
    }

    
    public static Set<String> resolve(Set<String> baselineKeys, Set<String> allowOverrides,
                                      Set<String> denyOverrides) {
        Set<String> allow = nullSafe(allowOverrides);
        Set<String> deny = nullSafe(denyOverrides);
        assertKeysKnown(allow);
        assertKeysKnown(deny);
        Set<String> effective = new LinkedHashSet<>(nullSafe(baselineKeys));
        effective.addAll(allow);
        effective.removeAll(deny);
        return Collections.unmodifiableSet(effective);
    }

    
    public static boolean hasPermission(RoleTemplate template, Set<String> allowOverrides,
                                        Set<String> denyOverrides, String key) {
        if (denyOverrides != null && denyOverrides.contains(key)) {
            return false;
        }
        if (allowOverrides != null && allowOverrides.contains(key)) {
            return true;
        }
        PermissionCatalog catalog = PermissionCatalog.fromKey(key);
        return catalog != null && template.families().contains(catalog.family());
    }

    private static void assertKeysKnown(Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> unknown = keys.stream().filter(k -> !PermissionCatalog.exists(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ValidationException("未知权限键：" + String.join("、", unknown)
                    + "；合法键以 " + KEY_PREFIX + "{域}:{动作} 格式为准（见权限目录 /permissions/catalog）");
        }
    }

    private static Set<String> nullSafe(Set<String> keys) {
        return keys == null ? Collections.emptySet() : keys;
    }

    
    public static Set<String> keyPrefixes() {
        Set<String> prefixes = new TreeSet<>();
        for (PermissionDomain d : PermissionDomain.values()) {
            prefixes.add(KEY_PREFIX + d.token() + ":");
        }
        return Collections.unmodifiableSet(prefixes);
    }
}
