package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.CollectionSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionUpdateRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ApiCollectionDomainPermissionWiringTest {

    
    private static final String KEY = "perm:detail:case-tag";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ApiCollectionController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "apitest 集合域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void apitest集合域五条链路全挂M3键恒放行零漂移() {
        assertEquals(KEY, permOf("list", long.class),
                "集合树=K6 成员/H1 隔离链路，挂 M3 键恒放行承 tags 手法");
        assertEquals(KEY, permOf("create", CollectionSaveRequest.class),
                "新增集合=assertCanWriteCase（成员且非开发），挂恒命中键零漂移、guard 保留");
        assertEquals(KEY, permOf("rename", long.class, CollectionUpdateRequest.class),
                "集合改名=同写路径面");
        assertEquals(KEY, permOf("delete", long.class),
                "删除集合=同写路径面（带子 409 在 core 单点）");
        assertEquals(KEY, permOf("get", long.class),
                "集合详情=K6 查看链路");

        String[][] wired = {
                {"GET", "/api/v1/apitest/collections"},
                {"POST", "/api/v1/apitest/collections"},
                {"PUT", "/api/v1/apitest/collections/7"},
                {"DELETE", "/api/v1/apitest/collections/7"},
                {"GET", "/api/v1/apitest/collections/7"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "apitest 挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void M3键族反证钉死四模板恒命中证据链() {
        PermissionCatalog m3 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.EDIT, m3.family(), "M3 键族=EDIT（证据链勿改）");
        assertEquals("M3", m3.functionId(), "M3 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            assertTrue(t.baselineKeys().contains(KEY),
                    t.name() + " 对 M3 键应恒命中（EDIT 族四模板基线均含）");
        }
    }

    @Test
    void 挂键端点不误伤相邻豁免面() {
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/apitest/collections/7"),
                "集合面 POST 只走集合路径，/{id} 字面段不得豁免");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/apitest/collections"),
                "删除链路只挂 /{id}，集合路径 DELETE 不得误豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
