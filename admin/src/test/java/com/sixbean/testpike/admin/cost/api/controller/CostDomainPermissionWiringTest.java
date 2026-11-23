package com.sixbean.testpike.admin.cost.api.controller;

import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CostDomainPermissionWiringTest {

    
    private static final String KEY = "perm:platform:cost-budget";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = CostController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("cost 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void cost域四条链路一挂三豁免零漂移() {
        assertEquals(KEY, permOf("breakOverride", long.class, BreakOverrideInput.class),
                "熔断临时恢复=纯 ADMIN 治理链路（02 §2.18 权限列 ADMIN），挂键零漂移");

        assertNull(permOf("overview", String.class, Long.class),
                "成本总览=单端点双权限面（全局 ADMIN/项目负责人），挂键缩负责人链路");
        assertNull(permOf("getBudget", long.class),
                "预算读取=assertCanManage 面（ADMIN∪负责人），挂键缩负责人");
        assertNull(permOf("putBudget", long.class, BudgetInput.class),
                "预算覆写=同上管理链路，键命中集 ⊊ 现行放行集禁挂");

        String[][] exempt = {
                {"GET", "/api/v1/cost/overview"},
                {"GET", "/api/v1/cost/budget/7"},
                {"PUT", "/api/v1/cost/budget/7"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "cost 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }

        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/cost/break-override/7"),
                "挂键链路禁登记豁免（双通道）");
    }

    @Test
    void E3键族反证钉死仅ADMIN命中证据链() {
        PermissionCatalog e3 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.MANAGE, e3.family(), "E3 键族=MANAGE（证据链勿改）");
        assertEquals("E3", e3.functionId(), "E3 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            boolean expectHit = t == RoleTemplate.ADMIN;
            assertEquals(expectHit, t.baselineKeys().contains(KEY),
                    t.name() + " 对 E3 键命中集预期=" + expectHit + "（MANAGE 仅 ADMIN 模板）");
        }
    }

    @Test
    void 豁免模板段不得外溢误吞() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/cost/budget/abc"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/cost/budget/7"),
                "方法敏感：budget 面只 GET/PUT，DELETE 不得误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/cost/overview"),
                "方法敏感：overview 只 GET");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/cost/break-override/7"),
                "方法敏感：break-override 只 POST（挂键链路亦不在豁免面）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
