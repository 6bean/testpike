package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class SuiteGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(long userId, String... roles) {
        return new CurrentUser(userId, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 项目负责人_管理动作_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanManage(user(1L, "TEST_LEAD"), 1L),
                "项目负责人可创建/编辑/删除/一键生成（TC-L3-01）");
    }

    @Test
    void 普通成员_管理动作_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(2L, "TESTER"), 1L),
                "普通成员执行负责人动作 → 403");
        assertEquals(40301, ex.getCode());
        assert (ex.getMessage().contains("项目负责人"));
    }

    @Test
    void 普通成员_查看_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user(2L, "TESTER"), 1L),
                "项目内成员可查看列表/详情");
    }

    @Test
    void 非项目成员_查看_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanView(user(3L, "TESTER"), 1L),
                "非项目成员查看 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 系统管理员_任意动作_通过() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanManage(user(99L, "ADMIN"), 1L),
                "系统管理员可执行全部动作");
        assertDoesNotThrow(() -> guard.assertCanView(user(99L, "ADMIN"), 1L));
    }
}
