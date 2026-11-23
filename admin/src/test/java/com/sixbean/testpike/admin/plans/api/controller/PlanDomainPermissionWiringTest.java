package com.sixbean.testpike.admin.plans.api.controller;

import com.sixbean.testpike.admin.plans.api.dto.PlanAssignRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanCreateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanUpdateRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PlanDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = PlanController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("plans 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void 功能点直接实现端点挂目录键零漂移() {
        assertEquals("perm:testops:progress-board", permOf("progress", long.class), "L7 计划进度");
        assertEquals("perm:testops:progress-board", permOf("progressSummary", long.class), "L7 多计划汇总");
    }

    @Test
    void 挂键会收窄合法用户或角色闸端点保持成员级并登记豁免() {
        assertNull(permOf("list", long.class, Integer.class, String.class, int.class, int.class,
                String.class, String.class),
                "计划列表=H1 成员级（挂 L1 CREATE 键拒合法只读开发成员=漂移）");
        assertNull(permOf("create", PlanCreateRequest.class),
                "创建计划=成员级（02 §2.12「项目内成员/负责人创建」，开发成员合法）");
        assertNull(permOf("get", long.class), "计划详情=H1 成员级");
        assertNull(permOf("listCases", long.class, Integer.class),
                "计划内用例明细=H1 成员级（分配弹窗数据源）");
        assertNull(permOf("update", long.class, PlanUpdateRequest.class), "编辑=负责人角色闸");
        assertNull(permOf("assign", long.class, PlanAssignRequest.class),
                "计划分配=负责人角色闸（tick27 裁决分配不建键）");
        assertNull(permOf("publish", long.class), "发布=负责人角色闸");
        assertNull(permOf("generateBatches", long.class), "生成批次=负责人角色闸");
        assertNull(permOf("completionCheck", long.class), "准出检查=负责人角色闸");
        assertNull(permOf("cancel", long.class), "取消=负责人角色闸");
        assertNull(permOf("delete", long.class, boolean.class), "删除=负责人角色闸（承 CASES_DELETE 先例）");
        assertNull(permOf("registerDeviation", long.class, PlanDeviationRequest.class),
                "偏差登记=成员级（M2 plan-lock 键=MANAGE 族拒 tester 合法成员=漂移）");
        assertNull(permOf("listDeviations", long.class), "偏差列表=成员级（02「项目内成员」，同上）");
        assertNull(permOf("validateCaseAllowed", long.class, long.class),
                "执行资格校验=成员级查询链路（M2 键 MANAGE 族禁挂）");

        String[][] memberLevel = {
                {"GET", "/api/v1/plans"},
                {"POST", "/api/v1/plans"},
                {"GET", "/api/v1/plans/7"},
                {"GET", "/api/v1/plans/7/cases"},
                {"PUT", "/api/v1/plans/7"},
                {"POST", "/api/v1/plans/7/assign"},
                {"POST", "/api/v1/plans/7/publish"},
                {"POST", "/api/v1/plans/7/generate-batches"},
                {"GET", "/api/v1/plans/7/completion-check"},
                {"POST", "/api/v1/plans/7/cancel"},
                {"DELETE", "/api/v1/plans/7"},
                {"POST", "/api/v1/plans/7/deviations"},
                {"GET", "/api/v1/plans/7/deviations"},
                {"GET", "/api/v1/plans/7/case-allowed"},
        };
        for (String[] ep : memberLevel) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "成员级端点未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 挂键端点不得混入豁免面() {
        String[][] keyed = {
                {"GET", "/api/v1/plans/7/progress"},
                {"GET", "/api/v1/plans/progress-summary"},
        };
        for (String[] ep : keyed) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "功能键端点不得登记豁免面：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 注解键必在册且族合法() {
        String key = "perm:testops:progress-board";
        PermissionCatalog catalog = PermissionCatalog.fromKey(key);
        assertNotNull(catalog, "注解键不在目录：" + key);
        assertEquals(PermActionFamily.VIEW, catalog.family(), "plans 域挂键族必须=VIEW：" + key);
    }
}
