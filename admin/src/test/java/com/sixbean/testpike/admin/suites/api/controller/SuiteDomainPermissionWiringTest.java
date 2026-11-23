package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.suites.api.dto.SuiteCreateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteUpdateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SuiteDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = SuiteController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "suites 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void suites域六条链路纯豁免零挂键() {
        assertNull(permOf("list", long.class, String.class, Integer.class, int.class, int.class,
                        String.class, String.class),
                "回归集列表=K6 成员/H1 隔离链路，挂 L3 CREATE 键缩 developer 只读");
        assertNull(permOf("get", long.class),
                "回归集详情=K6 成员/H1 隔离链路，同上");
        assertNull(permOf("create", SuiteCreateRequest.class),
                "创建=assertCanManage（ADMIN∪负责人），挂 CREATE 键放大非成员 tester=漂移");
        assertNull(permOf("update", long.class, SuiteUpdateRequest.class),
                "编辑=负责人角色闸，键命中集双向不等禁挂");
        assertNull(permOf("delete", long.class),
                "删除=负责人角色闸，同上");
        assertNull(permOf("run", long.class),
                "一键生成计划/批次=负责人角色闸（TC-L3-01），同上");

        String[][] exempt = {
                {"GET", "/api/v1/suites"},
                {"POST", "/api/v1/suites"},
                {"GET", "/api/v1/suites/7"},
                {"PUT", "/api/v1/suites/7"},
                {"DELETE", "/api/v1/suites/7"},
                {"POST", "/api/v1/suites/7/run"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "suites 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/suites/abc"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/suites/abc/run"),
                "{id} 模板禁吞字面段（run 链路同律）");
        assertFalse(PublicEndpointRegistry.isPublic("PATCH", "/api/v1/suites"),
                "方法敏感：集合面只 GET/POST（PATCH 不得误豁免）");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/suites/7/run"),
                "方法敏感：/run 面只 POST");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
