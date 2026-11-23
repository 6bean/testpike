package com.sixbean.testpike.admin.skill.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.skill.api.dto.SkillView;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SkillAccessGuardTest {

    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final SkillAccessGuard guard = new SkillAccessGuard(projectGuard);

    private static CurrentUser user(long userId, String... roles) {
        return new CurrentUser(userId, "u", "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
    }

    
    private static SkillView projectSkill(Long projectId, Long createdBy) {
        return new SkillView(1L, projectId, "用例设计助手", 1, "用例设计",
                1, "草稿", 1, 1, "正文预览", null,
                createdBy, null, null, null, "2026-09-09 10:00", "2026-09-09 10:00");
    }


    @Test
    void 系统级SKILL_非ADMIN管理_403() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(30L, "TESTER"), null));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话须点出角色要求: " + ex.getMessage());

        assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(31L, "TEST_LEAD"), null),
                "测试负责人非系统管理员，系统级 SKILL 不得管理");
    }

    @Test
    void 系统级SKILL_ADMIN管理_通过() {
        assertDoesNotThrow(() -> guard.assertCanManage(user(99L, "ADMIN"), null));
    }

    @Test
    void 项目级SKILL_项目负责人_通过() {
        projectClient.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanManage(user(20L, "TESTER"), 1L),
                "项目负责人可管理本项目 SKILL（02 权限矩阵）");
    }

    @Test
    void 项目级SKILL_ADMIN_通过() {
        projectClient.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanManage(user(99L, "ADMIN"), 1L));
    }

    @Test
    void 项目级SKILL_普通成员_403() {
        projectClient.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(21L, "TESTER"), 1L));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("项目负责人"), () -> "人话含角色要求: " + ex.getMessage());
    }

    @Test
    void 项目级SKILL_非成员_403() {
        projectClient.memberRoleResult(0);
        assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user(22L, "TESTER"), 1L),
                "H1 隔离：非本项目成员不得管理（复用 assertCanManage 基径）");
    }


    @Test
    void 提交审批_创建人本人_通过() {
        assertDoesNotThrow(() -> guard.assertCanSubmit(user(10L, "TESTER"), projectSkill(1L, 10L)));
    }

    @Test
    void 提交审批_非创建人_403() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanSubmit(user(11L, "TESTER"), projectSkill(1L, 10L)));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("创建人"), () -> "人话含角色要求: " + ex.getMessage());
    }

    @Test
    void 提交审批_ADMIN亦非创建人_403() {
        assertThrows(ServiceException.class,
                () -> guard.assertCanSubmit(user(99L, "ADMIN"), projectSkill(1L, 10L)));
    }
}
