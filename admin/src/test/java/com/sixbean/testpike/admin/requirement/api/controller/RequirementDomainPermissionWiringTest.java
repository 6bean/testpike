package com.sixbean.testpike.admin.requirement.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.requirement.api.dto.CreateRequirementRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkCasesRequest;
import com.sixbean.testpike.admin.requirement.api.dto.UpdateRequirementRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RequirementDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = RequirementController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("requirement 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void requirement域混合接线_trace链挂P3键_其余九条链路豁免() {
        assertEquals("perm:ux:trace-view",
                permOf("requirementTraceChain", long.class),
                "需求追溯链=P3 VIEW 键（skills 恒放行手法）");
        assertEquals("perm:ux:trace-view",
                permOf("caseTraceChain", long.class),
                "用例追溯链=P3 VIEW 键（同口径）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/requirements/7/trace-chain"),
                "挂键链路禁登记豁免（双通道=接线审计盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/cases/7/trace-chain"),
                "挂键链路禁登记豁免");

        // ids allow-list param appended by the structured deep-link handoff,
        // fingerprint appended by the exact content-fingerprint filter (same arity
        // discipline: this guard is checked against the controller after every
        // signature change, not only before it)
        assertNull(permOf("list", long.class, Integer.class, String.class, int.class, int.class,
                        String.class, String.class, String.class, String.class),
                "需求列表=成员只读路径（域内无贴语义 VIEW 键可借）");
        assertNull(permOf("listByCaseId", long.class), "用例侧需求列表=成员只读路径");
        assertNull(permOf("listLinkedCases", long.class), "关联用例列表=成员只读路径");
        assertNull(permOf("create", long.class, CreateRequirementRequest.class),
                "创建=负责人/ADMIN 且非开发角色闸（挂键命中集双向不等）");
        assertNull(permOf("update", long.class, long.class, UpdateRequirementRequest.class),
                "编辑=同上（停用联动在 core）");
        assertNull(permOf("delete", long.class, long.class), "删除=同上（被关联 40904 透传）");
        assertNull(permOf("linkCases", long.class, LinkCasesRequest.class),
                "关联用例=成员且非开发闸（挂 CREATE/RUN 键放大或缩=漂移禁挂）");
        assertNull(permOf("unlinkCase", long.class, long.class), "需求侧取消关联=同关联链路");
        assertNull(permOf("unlinkCaseFromCase", long.class, long.class), "用例侧取消关联=同关联链路");

        String[][] exempt = {
                {"GET", "/api/v1/projects/7/requirements"},
                {"POST", "/api/v1/projects/7/requirements"},
                {"PUT", "/api/v1/projects/7/requirements/8"},
                {"DELETE", "/api/v1/projects/7/requirements/8"},
                {"POST", "/api/v1/requirements/8/cases"},
                {"DELETE", "/api/v1/requirements/8/cases/9"},
                {"DELETE", "/api/v1/cases/9/requirements/8"},
                {"GET", "/api/v1/cases/9/requirements"},
                {"GET", "/api/v1/requirements/8/cases"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "requirement 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不得外溢误吞() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/requirements/abc/cases"),
                "{id} 模板禁吞字面段");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/cases/abc/requirements"),
                "{id} 模板禁吞字面段");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/7/requirements/8"),
                "方法敏感：/{reqId} 面无 POST 链路");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/requirements/8/cases"),
                "方法敏感：取消关联必须带 caseId 段，裸父路径 DELETE 不在豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/cases/9/requirements/8"),
                "GET 用例侧列表模板禁外溢吞双段路径（新豁免段与 CASES_GET 等既有面互斥反证）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/cases/9/requirements/8"),
                "方法敏感：用例侧取消=DELETE，POST 不得误豁免");
    }

    @Test
    void P3键族反证钉死恒放行证据链() {
        for (RoleTemplate t : RoleTemplate.values()) {
            Set<PermActionFamily> families = t.families();
            assertTrue(families.contains(PermActionFamily.VIEW),
                    t.name() + " 模板不含 VIEW 族（恒放行前提勿改）");
        }
        PermissionCatalog p3 = PermissionCatalog.fromKey("perm:ux:trace-view");
        assertEquals(PermActionFamily.VIEW, p3.family(), "P3 键族=VIEW（证据链勿改）");
        assertEquals("P3", p3.functionId(), "P3 键映射功能点勿改");
    }
}
