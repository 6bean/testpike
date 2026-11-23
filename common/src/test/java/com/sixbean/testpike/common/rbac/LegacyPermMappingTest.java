package com.sixbean.testpike.common.rbac;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LegacyPermMappingTest {

    @Test
    void 四键投影到目录新键_且新键均在147目录() {
        assertEquals("perm:system:user-manage", LegacyPermMapping.toCatalogKey("user:manage"));
        assertEquals("perm:system:role-manage", LegacyPermMapping.toCatalogKey("role:manage"));
        assertEquals("perm:system:system-config", LegacyPermMapping.toCatalogKey("config:manage"));
        assertEquals("perm:system:llm-config", LegacyPermMapping.toCatalogKey("llm:config"));
        for (String legacy : LegacyPermMapping.catalogKeys()) {
            assertTrue(PermissionCatalog.exists(legacy), "映射目标必须是 147 目录在册键: " + legacy);
        }
    }

    @Test
    void auditView投影到新键_键裁决落地() {
        assertEquals("perm:detail:audit-view", LegacyPermMapping.toCatalogKey("audit:view"));
        assertTrue(LegacyPermMapping.catalogKeys().contains("perm:detail:audit-view"));
    }

    @Test
    void project投影_收录键换形未收录键原样保留() {
        Set<String> in = Set.of("user:manage", "case:review", "perm:skill:approve");
        Set<String> out = LegacyPermMapping.project(in);
        assertEquals(Set.of("perm:system:user-manage", "case:review", "perm:skill:approve"), out,
                "已收录=替换形态；未收录（case:review 无注解消费方）与 perm: 键原样直通");
    }

    @Test
    void project空集与null安全() {
        assertEquals(Set.of(), LegacyPermMapping.project(Set.of()));
        assertEquals(Set.of(), LegacyPermMapping.project(null));
    }

    @Test
    void toCatalogKey未知键返回null() {
        assertEquals(null, LegacyPermMapping.toCatalogKey("nope:nope"));
    }
}
