package com.sixbean.testpike.admin.plans.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class PlanBoardGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(long userId, String... roles) {
        return new CurrentUser(userId, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 项目成员_看板汇总_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user(2L, "TESTER"), 1L),
                "项目内成员可查看多计划汇总看板（TC-L7-01）");
    }

    @Test
    void 非项目成员_看板汇总_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanView(user(3L, "TESTER"), 1L),
                "非项目成员查看进度汇总 → 403（IT-L7-01，H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 系统管理员_看板汇总_通过() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanView(user(99L, "ADMIN"), 1L),
                "系统管理员可查看任意项目看板");
    }

    @Test
    void 项目成员_计划详情进度_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user(2L, "TESTER"), 1L),
                "项目内成员可查看计划详情进度（/plans/{id}/progress）");
    }

    @Test
    void 非项目成员_计划详情进度_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanView(user(3L, "TESTER"), 1L),
                "非项目成员查看计划详情进度 → 403（IT-L7-01）");
        assertEquals(40301, ex.getCode());
    }
}
