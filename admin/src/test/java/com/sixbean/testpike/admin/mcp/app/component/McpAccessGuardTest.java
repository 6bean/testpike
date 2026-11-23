package com.sixbean.testpike.admin.mcp.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpAccessGuardTest {

    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(new StubCoreProjectClient());
    private final McpAccessGuard guard = new McpAccessGuard(projectGuard);

    private static CurrentUser user(long userId, String... roles) {
        return new CurrentUser(userId, "u", "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void ADMIN_管理MCP_通过() {
        assertDoesNotThrow(() -> guard.assertCanManage(user(99L, "ADMIN")));
    }

    @Test
    void TEST_LEAD_管理MCP_403() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(31L, "TEST_LEAD")));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"),
                () -> "人话须点出角色要求: " + ex.getMessage());
    }

    @Test
    void TESTER_管理MCP_403() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(30L, "TESTER")));
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 多角色含ADMIN_通过() {
        assertDoesNotThrow(() -> guard.assertCanManage(user(50L, "TESTER", "ADMIN")),
                "角色列表含 ADMIN 即放行（hasRole 语义与 ProjectAccessGuard 一致）");
    }

    @Test
    void 空角色_403() {
        assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(60L)),
                "无角色登录人不得管理 MCP");
    }
}
