package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.mcp.api.dto.McpServerCreateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerUpdateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpToolSelectionRequest;
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


class McpDomainPermissionWiringTest {

    
    private static final String KEY = "perm:knowledge:mcp-manage";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = McpServerController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("mcp 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void mcp域八条链路纯ADMIN挂G7键零漂移() {
        assertEquals(KEY, permOf("list", String.class, Integer.class, Integer.class,
                String.class, String.class, int.class, int.class),
                "列表=读面亦 ADMIN（02 权限列，内网可达信息不放宽）");
        assertEquals(KEY, permOf("create", McpServerCreateRequest.class), "注册");
        assertEquals(KEY, permOf("get", long.class), "详情（auth 打码在 core）");
        assertEquals(KEY, permOf("update", long.class, McpServerUpdateRequest.class), "编辑");
        assertEquals(KEY, permOf("delete", long.class), "删除（40904 引用守在 core）");
        assertEquals(KEY, permOf("toggle", long.class, int.class), "启停");
        assertEquals(KEY, permOf("updateTools", long.class, McpToolSelectionRequest.class), "工具勾选保存");
        assertEquals(KEY, permOf("test", long.class), "连接测试（IT-MCP-05 恒 200 在 core）");

        String[][] wired = {
                {"GET", "/api/v1/mcp-servers"},
                {"POST", "/api/v1/mcp-servers"},
                {"GET", "/api/v1/mcp-servers/7"},
                {"PUT", "/api/v1/mcp-servers/7"},
                {"DELETE", "/api/v1/mcp-servers/7"},
                {"PUT", "/api/v1/mcp-servers/7/toggle"},
                {"PUT", "/api/v1/mcp-servers/7/tools"},
                {"POST", "/api/v1/mcp-servers/7/test"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void G7键族反证钉死仅ADMIN命中证据链() {
        PermissionCatalog g7 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.MANAGE, g7.family(), "G7 键族=MANAGE（证据链勿改）");
        assertEquals("G7", g7.functionId(), "G7 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            boolean expectHit = t == RoleTemplate.ADMIN;
            assertEquals(expectHit, t.baselineKeys().contains(KEY),
                    t.name() + " 对 G7 键命中集预期=" + expectHit + "（MANAGE 仅 ADMIN 模板）");
        }
    }

    @Test
    void 豁免面不得外溢误吞mcp路径() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/mcp-servers/abc"),
                "非数字段不得被吞（坑29 反向核对）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/mcp-servers/7/toggle"),
                "方法敏感：toggle 只 PUT");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
