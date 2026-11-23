package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class DefectAccessGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 关联_项目内测试人员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanWriteCase(user("TESTER"), 1L),
                "项目内测试人员可关联缺陷（TC-J2-03）");
    }

    @Test
    void 关联_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteCase(user("TESTER"), 1L),
                "非项目成员关联缺陷 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 回填closed_项目负责人_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanBackfillDefectStatus(user("TEST_LEAD"), 1L, "closed"),
                "仅项目负责人可回填 closed（TC-J2-03）");
    }

    @Test
    void 回填closed_系统管理员_通过() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanBackfillDefectStatus(user("ADMIN"), 1L, "closed"),
                "系统管理员可回填 closed（PRD 2 章）");
    }

    @Test
    void 回填closed_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanBackfillDefectStatus(user("TESTER"), 1L, "closed"),
                "普通成员回填 closed → 403（仅负责人）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 回填open_普通成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanBackfillDefectStatus(user("TESTER"), 1L, "open"),
                "非 closed 状态项目成员可回填");
    }

    @Test
    void 开发人员_关联与回填_均403() {
        client.memberRoleResult(2);
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteCase(user("DEVELOPER"), 1L),
                "开发角色只读（TC-AUTH-03）");
        assertEquals(40301, ex1.getCode());
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> guard.assertCanBackfillDefectStatus(user("DEVELOPER"), 1L, "closed"));
        assertEquals(40301, ex2.getCode());
    }
}
