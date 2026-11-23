package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateView;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthRequest;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthView;
import com.sixbean.testpike.admin.mcp.app.component.McpAccessGuard;
import com.sixbean.testpike.admin.mcp.infrastructure.remote.client.McpAuthClient;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class McpAuthBridgeTest {

    
    private static final String KEY = "perm:knowledge:mcp-manage";

    private final McpAuthClient mcpAuthClient = mock(McpAuthClient.class);
    private final McpAccessGuard mcpGuard = mock(McpAccessGuard.class);
    private final McpAuthController controller = new McpAuthController(mcpAuthClient, mcpGuard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private CurrentUser login() {
        CurrentUser user = new CurrentUser(7L, "u", "名", "部", List.of("ADMIN"), 0L, null);
        UserContext.set(user);
        return user;
    }

    private McpAuthTemplateView template(long id) {
        return new McpAuthTemplateView(id, "冒烟工具集", List.of("click", "input"),
                "备注", 1, null, "2026-09-26 08:00:00", "2026-09-26 08:00:00");
    }

    
    @Test
    void templateListPassesThrough() {
        CurrentUser user = login();
        when(mcpAuthClient.listTemplates()).thenReturn(List.of(template(1L), template(2L)));

        List<McpAuthTemplateView> result = controller.listTemplates().getData();

        verify(mcpGuard).assertCanManage(user);
        assertEquals(2, result.size());
        assertEquals(List.of("click", "input"), result.get(0).tools(), "工具清单保序透传");
    }

    
    @Test
    void nonAdminRejectedBeforeCoreCall() {
        login();
        doThrow(new ServiceException("「MCP 服务」页仅系统管理员可管理：如需注册或修改 MCP 服务，请联系系统管理员用管理员账号在「MCP 服务」页操作", BizErrorCode.FORBIDDEN))
                .when(mcpGuard).assertCanManage(any(CurrentUser.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listTemplates());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(mcpAuthClient);
    }

    
    @Test
    void createAndUpdatePassThrough() {
        login();
        McpAuthTemplateRequest request =
                new McpAuthTemplateRequest("冒烟工具集", List.of("click"), null, 1, null);
        when(mcpAuthClient.create(request)).thenReturn(template(9L));
        when(mcpAuthClient.update(9L, request)).thenReturn(template(9L));

        assertEquals(9L, controller.createTemplate(request).getData().id());
        assertEquals("冒烟工具集", controller.updateTemplate(9L, request).getData().name());
        verify(mcpAuthClient).update(9L, request);
    }

    
    @Test
    void deleteAndApplyPassThrough() {
        login();
        controller.deleteTemplate(9L);
        verify(mcpAuthClient).delete(9L);

        McpAuthApplyRequest apply = new McpAuthApplyRequest(List.of(1L, 2L));
        when(mcpAuthClient.apply(9L, apply)).thenReturn(new McpAuthApplyResult(1,
                List.of("服务A：新启用2个", "服务B：跳过(快照外)：ghost_tool")));
        McpAuthApplyResult result = controller.apply(9L, apply).getData();

        assertEquals(1, result.updatedServers());
        assertEquals(2, result.details().size(), "明细逐服务全量透出不截断");
        assertEquals("服务B：跳过(快照外)：ghost_tool", result.details().get(1));
    }

    
    @Test
    void projectAuthGetPassesNullAsUnconfigured() {
        login();
        when(mcpAuthClient.getProjectAuth(3L)).thenReturn(null);
        assertNull(controller.getProjectAuth(3L).getData(), "无行=直通语义不得被桥改写");
    }

    
    @Test
    void projectAuthPutPassThrough() {
        login();
        ProjectMcpAuthRequest request = new ProjectMcpAuthRequest(5L, 1);
        when(mcpAuthClient.putProjectAuth(3L, request))
                .thenReturn(new ProjectMcpAuthView(3L, 5L, "冒烟工具集", 1));

        ProjectMcpAuthView result = controller.putProjectAuth(3L, request).getData();
        assertEquals("冒烟工具集", result.templateName(), "join 名 join 现算随 core 视图透传");
    }

    
    @Test
    void sevenLegsWiredWithG7KeyAndNoExemption() throws Exception {
        assertEquals(KEY, permOf("listTemplates"), "清单链路");
        assertEquals(KEY, permOf("createTemplate", McpAuthTemplateRequest.class), "新建链路");
        assertEquals(KEY, permOf("updateTemplate", long.class, McpAuthTemplateRequest.class), "编辑链路");
        assertEquals(KEY, permOf("deleteTemplate", long.class), "删除链路");
        assertEquals(KEY, permOf("apply", long.class, McpAuthApplyRequest.class), "套用链路");
        assertEquals(KEY, permOf("getProjectAuth", long.class), "继承读路径");
        assertEquals(KEY, permOf("putProjectAuth", long.class, ProjectMcpAuthRequest.class), "继承写路径");

        String[][] wired = {
                {"GET", "/api/v1/mcp-auth/templates"},
                {"POST", "/api/v1/mcp-auth/templates"},
                {"PUT", "/api/v1/mcp-auth/templates/7"},
                {"DELETE", "/api/v1/mcp-auth/templates/7"},
                {"POST", "/api/v1/mcp-auth/templates/7/apply"},
                {"GET", "/api/v1/mcp-auth/projects/3"},
                {"PUT", "/api/v1/mcp-auth/projects/3"},
        };
        for (String[] ep : wired) {
            assertFalse(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "挂键链路禁登记豁免（双通道）：" + ep[0] + " " + ep[1]);
        }
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/mcp-auth/templates/abc"));
    }

    
    private static String permOf(String methodName, Class<?>... params) throws Exception {
        Method m = McpAuthController.class.getMethod(methodName, params);
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        return ann == null ? null : ann.value();
    }
}
