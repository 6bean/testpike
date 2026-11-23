package com.sixbean.testpike.admin.coverage.api.controller;

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


class CoverageDomainPermissionWiringTest {

    
    private static final String KEY = "perm:batch:coverage-matrix";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = CoverageController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("coverage 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void coverage域单链路挂J4键恒放行零漂移() {
        assertEquals(KEY, permOf("matrix", long.class, String.class, Integer.class, String.class),
                "覆盖矩阵=J4 贴语义 VIEW 族键，挂键恒放行承 skills 手法");

        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/projects/7/coverage"),
                "挂键链路禁登记豁免（双通道）");
    }

    @Test
    void J4键族反证钉死四模板恒命中证据链() {
        PermissionCatalog j4 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.VIEW, j4.family(), "J4 键族=VIEW（证据链勿改）");
        assertEquals("J4", j4.functionId(), "J4 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            assertTrue(t.baselineKeys().contains(KEY),
                    t.name() + " 对 J4 键应恒命中（VIEW 族四模板基线均含）");
        }
    }

    @Test
    void 挂键端点不误伤相邻豁免面() {
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/projects/7/coverage"),
                "coverage 只 GET，POST 不得误豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
