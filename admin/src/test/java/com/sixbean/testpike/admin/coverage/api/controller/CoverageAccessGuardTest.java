package com.sixbean.testpike.admin.coverage.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class CoverageAccessGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 矩阵_项目成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanViewCoverage(user("TESTER"), 1L),
                "项目内测试人员可查看覆盖矩阵（PRD 4.2.3 项目内可见）");
    }

    @Test
    void 矩阵_开发成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanViewCoverage(user("DEVELOPER"), 1L),
                "开发成员按授权范围可查看覆盖矩阵（15 文档矩阵覆盖率行 ✅）");
    }

    @Test
    void 矩阵_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanViewCoverage(user("TESTER"), 1L),
                "非项目成员查看覆盖矩阵 → 403（IT-J4-01，H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 矩阵_系统管理员_通过() {
        assertDoesNotThrow(() -> guard.assertCanViewCoverage(user("ADMIN"), 1L),
                "系统管理员可查看任意项目覆盖矩阵（15 文档矩阵 ✅）");
    }

    @Test
    void 矩阵_测试负责人_通过() {
        assertDoesNotThrow(() -> guard.assertCanViewCoverage(user("TEST_LEAD"), 1L),
                "测试负责人可查看覆盖矩阵（系统级管理角色）");
    }
}
