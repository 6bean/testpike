package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.RequestSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunCreateRequest;
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


class ApiRequestRunDomainPermissionWiringTest {

    
    private static final String KEY = "perm:detail:case-tag";

    
    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "apitest 请求/运行域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void apitest请求与运行域十条链路全挂M3键恒放行零漂移() {
        assertEquals(KEY, permOf(ApiRequestController.class, "list", long.class),
                "集合内请求列表=K6 成员/H1 隔离链路，挂 M3 键恒放行承 tags 手法");
        assertEquals(KEY, permOf(ApiRequestController.class, "create", RequestSaveRequest.class),
                "新增请求=assertCanWriteCase（成员且非开发），挂恒命中键零漂移、guard 保留");
        assertEquals(KEY, permOf(ApiRequestController.class, "update", long.class, RequestSaveRequest.class),
                "更新请求=同写路径面（SSRF/断言校验在 core 单点）");
        assertEquals(KEY, permOf(ApiRequestController.class, "delete", long.class),
                "删除请求=同写路径面（带运行 409 在 core 单点）");
        assertEquals(KEY, permOf(ApiRequestController.class, "get", long.class),
                "请求详情=K6 查看链路");

        assertEquals(KEY, permOf(ApiRunController.class, "create", RunCreateRequest.class),
                "发起调试运行=assertCanWriteCase（环境归属 400 守卫在 core 单点勿重复）");
        assertEquals(KEY, permOf(ApiRunController.class, "get", long.class),
                "运行详情=K6 查看链路");
        assertEquals(KEY, permOf(ApiRunController.class, "listByRequest", long.class),
                "运行历史=K6 查看链路");
        assertEquals(KEY, permOf(ApiRunController.class, "delete", long.class),
                "删单条运行=写路径面");
        assertEquals(KEY, permOf(ApiRunController.class, "clear", long.class),
                "清空未升格运行历史=写路径面（已升格保留追溯在 core 单点）");

        String[][] wired = {
                {"GET", "/api/v1/apitest/requests"},
                {"POST", "/api/v1/apitest/requests"},
                {"PUT", "/api/v1/apitest/requests/7"},
                {"DELETE", "/api/v1/apitest/requests/7"},
                {"GET", "/api/v1/apitest/requests/7"},
                {"POST", "/api/v1/apitest/runs"},
                {"GET", "/api/v1/apitest/runs/7"},
                {"GET", "/api/v1/apitest/runs"},
                {"DELETE", "/api/v1/apitest/runs/7"},
                {"DELETE", "/api/v1/apitest/runs"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "apitest 请求/运行挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
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
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/apitest/requests/7"),
                "请求面 POST 只走列表路径，/{id} 字面段不得豁免");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/apitest/runs/7"),
                "运行结果回写走 core internal 回传链路，admin 无 PUT /runs/{id} 不得误豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
