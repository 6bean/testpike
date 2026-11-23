package com.sixbean.testpike.common.rbac;

import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PermissionResolverTest {

    private static final String GEN_KEY = "perm:case:case-generate";
    private static final String REVIEW_KEY = "perm:system:role-manage";
    private static final String APPROVE_KEY = "perm:skill:approve";
    private static final String VIEW_KEY = "perm:ux:global-search";

    @Test
    void auditViewKeyIsAdminOnlyNotInViewTemplates() {
        String auditKey = "perm:detail:audit-view";
        assertEquals(PermActionFamily.MANAGE, PermissionCatalog.fromKey(auditKey).family(),
                "审计查看键族归属=MANAGE（admin-only），VIEW 族会命中 developer 模板=越权漂移");
        for (RoleTemplate t : new RoleTemplate[]{RoleTemplate.DEVELOPER, RoleTemplate.TESTER,
                RoleTemplate.TEST_LEAD}) {
            assertFalse(t.baselineKeys().contains(auditKey),
                    t.code() + " 模板基线不得含审计查看键（IT-M1-01 非管理员 403 链路）");
        }
        assertTrue(RoleTemplate.ADMIN.baselineKeys().contains(auditKey),
                "ADMIN 模板全量展开必含审计查看键");
    }

    @Test
    void denyBeatsExplicitAllow() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.TESTER,
                Set.of(APPROVE_KEY), Set.of(APPROVE_KEY));
        assertFalse(r.contains(APPROVE_KEY), "显式拒绝必须压过显式允许");
    }

    @Test
    void allowAddsBeyondTemplate() {
        Set<String> base = PermissionResolver.resolve(RoleTemplate.DEVELOPER, Set.of(), Set.of());
        assertFalse(base.contains(GEN_KEY), "模板基线断言前提：DEVELOPER 默认不含 CREATE 族");
        Set<String> r = PermissionResolver.resolve(RoleTemplate.DEVELOPER, Set.of(GEN_KEY), Set.of());
        assertTrue(r.contains(GEN_KEY), "显式允许必须并入（模板默认之上叠加）");
    }

    @Test
    void templateDefaultWhenNoOverride() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.TESTER, Set.of(), Set.of());
        assertTrue(r.contains(GEN_KEY), "tester=查看+执行+生成族");
        assertTrue(r.contains(VIEW_KEY));
        assertFalse(r.contains(APPROVE_KEY), "审批族属 test_lead+（模板基线语义）");
        assertFalse(r.contains(REVIEW_KEY), "MANAGE 族仅 ADMIN");
        assertTrue(r.containsAll(PermissionResolver.resolve(RoleTemplate.DEVELOPER, Set.of(), Set.of())),
                "TESTER 模板应覆盖 DEVELOPER 只读族（模板单调）");
    }

    @Test
    void denySubtractsTemplateDefault() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.TEST_LEAD, Set.of(), Set.of(APPROVE_KEY));
        assertTrue(r.contains(APPROVE_KEY) == false, "deny 剔除模板默认授予项");
        assertTrue(r.contains(GEN_KEY), "其余模板项不受牵连");
    }

    @Test
    void adminExpandsFullCatalogNotBareWildcard() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.ADMIN, Set.of(), Set.of());
        assertEquals(PermissionCatalog.values().length, r.size());
        assertFalse(r.contains("*"), "禁止裸通配符混入裁决结果");
        assertTrue(r.contains(REVIEW_KEY), "MANAGE 族对 ADMIN 展开");
        assertFalse(PermissionResolver.resolve(RoleTemplate.ADMIN, Set.of(), Set.of(VIEW_KEY)).contains(VIEW_KEY));
    }

    @Test
    void unknownPermKeyRejected() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> PermissionResolver.resolve(RoleTemplate.TESTER, Set.of("perm:no.such:key"), Set.of()));
        assertTrue(e.getMessage().contains("perm:no.such:key"), "报错必须点名非法键: " + e.getMessage());
        assertTrue(e.getMessage().contains("perm:"), "报错必须列出合法前缀: " + e.getMessage());
        assertThrows(ValidationException.class,
                () -> PermissionResolver.resolve(RoleTemplate.TESTER, Set.of(), Set.of("bare-key")));
    }

    @Test
    void nullOverridesTreatedAsEmpty() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.TESTER, null, null);
        assertFalse(r.isEmpty());
    }

    @Test
    void resultImmutable() {
        Set<String> r = PermissionResolver.resolve(RoleTemplate.DEVELOPER, Set.of(), Set.of());
        assertThrows(UnsupportedOperationException.class, () -> r.add("perm:x:y"));
    }

    @Test
    void baselineKeysOverloadEqualsTemplateDelegate() {
        Set<String> viaTemplate = PermissionResolver.resolve(RoleTemplate.TESTER, Set.of(GEN_KEY), Set.of(VIEW_KEY));
        Set<String> viaBaseline = PermissionResolver.resolve(
                RoleTemplate.TESTER.baselineKeys(), Set.of(GEN_KEY), Set.of(VIEW_KEY));
        assertEquals(viaTemplate, viaBaseline, "两重载必须同结果");
        Set<String> custom = PermissionResolver.resolve(Set.of(), Set.of(APPROVE_KEY), Set.of());
        assertEquals(Set.of(APPROVE_KEY), custom, "空基线+allow=自定义角色语义");
    }

    @Test
    void projectManagerTemplateIsRunlessBranchOfTestLead() {
        RoleTemplate pm = RoleTemplate.fromCode("project_manager");
        assertTrue(pm != null, "project_manager 必须可解析为内置角色模板");
        Set<String> keys = pm.baselineKeys();
        assertTrue(keys.contains(VIEW_KEY), "PM 基线含 VIEW 族");
        assertTrue(keys.contains(GEN_KEY), "PM 基线含 CREATE 族（用例生成）");
        assertTrue(keys.contains(APPROVE_KEY), "PM 基线含 APPROVE 族（计划与审批）");
        assertFalse(keys.contains("perm:script:trial-run"), "PM 基线不得含 RUN 族（执行留测试人员）");
        assertFalse(keys.contains(REVIEW_KEY), "PM 基线不得含 MANAGE 族（角色管理留管理员）");
        assertFalse(keys.contains("perm:detail:audit-view"), "PM 不得看审计（admin-only MANAGE 族键）");
        assertTrue(keys.containsAll(RoleTemplate.DEVELOPER.baselineKeys()), "链：developer ⊆ PM");
        assertTrue(RoleTemplate.TEST_LEAD.baselineKeys().containsAll(keys), "链：PM ⊆ test_lead");
        assertTrue(RoleTemplate.ADMIN.baselineKeys().containsAll(keys), "链：PM ⊆ admin");
    }
}
