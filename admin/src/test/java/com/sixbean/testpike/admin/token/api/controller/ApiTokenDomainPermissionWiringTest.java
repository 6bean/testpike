package com.sixbean.testpike.admin.token.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ApiTokenDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ApiTokenController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "token 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void token域四条链路纯豁免零挂键() {
        // Assertion ①: four links carry zero permission annotations (no semantically fitting key exists;
        // usage = GA3 observability read, owner ∪ admin read-through decided once in core, a MANAGE key
        // would shrink the normal-user self-service surface — see class note).
        assertNull(permOf("list"), "本人 Token 列表=属主自助链路，无功能键语义");
        assertNull(permOf("create", ApiTokenCreateRequest.class),
                "创建=属主自助链路（明文一次性在 core 单点）");
        assertNull(permOf("revoke", long.class),
                "吊销=本人+ADMIN 判定在 core（越权 40301 人话），禁挂 MANAGE 键缩普通用户");
        assertNull(permOf("usage", long.class),
                "用量统计=本人+ADMIN 代看判定在 core（非属主诚实拒止），禁挂键缩自助面");

        String[][] exempt = {
                {"GET", "/api/v1/api-tokens"},
                {"POST", "/api/v1/api-tokens"},
                {"DELETE", "/api/v1/api-tokens/7/revoke"},
                {"GET", "/api/v1/api-tokens/7/usage"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "token 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/api-tokens/abc/revoke"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/api-tokens/7/revoke"),
                "方法敏感：/revoke 面只 DELETE");
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/api-tokens"),
                "方法敏感：集合面只 GET/POST（PUT 不得误豁免）");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
