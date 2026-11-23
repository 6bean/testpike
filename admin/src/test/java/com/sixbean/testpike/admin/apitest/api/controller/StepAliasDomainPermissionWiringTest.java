package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.StepAliasSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasUpdateRequest;
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


class StepAliasDomainPermissionWiringTest {

    
    private static final String KEY = "perm:detail:case-tag";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = StepAliasController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "apitest 别名域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void apitest别名域四条链路全挂M3键恒放行零漂移() {
        assertEquals(KEY, permOf("list", long.class),
                "项目别名列表=K6 查看链路（引用下拉别名分组消费面），挂 M3 键恒放行承 tags 手法");
        assertEquals(KEY, permOf("create", StepAliasSaveRequest.class),
                "登记别名=assertCanWriteCase（成员且非开发），挂恒命中键零漂移、guard 保留");
        assertEquals(KEY, permOf("update", long.class, StepAliasUpdateRequest.class),
                "改别名指向=同写路径面（调整性兼容主入口，三冲突闸在 core 单点）");
        assertEquals(KEY, permOf("delete", long.class),
                "删除别名=同写路径面（引用回退未登记穿透拒语义在 core 单点）");

        String[][] wired = {
                {"GET", "/api/v1/apitest/step-aliases"},
                {"POST", "/api/v1/apitest/step-aliases"},
                {"PUT", "/api/v1/apitest/step-aliases/7"},
                {"DELETE", "/api/v1/apitest/step-aliases/7"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "apitest 别名域挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
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
}
