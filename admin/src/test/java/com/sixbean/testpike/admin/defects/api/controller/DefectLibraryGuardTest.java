package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.defects.api.dto.DefectLibraryView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class DefectLibraryGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(long userId, String... roles) {
        return new CurrentUser(userId, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    
    private static DefectLibraryView defect() {
        return new DefectLibraryView(1L, 1L, null, null, "优惠券金额不符", "满减叠加错误",
                2, 1, 10L, "修复人", 20L, "报告人", null, null, null, 1, 0, null, 1, "t", "t");
    }

    @Test
    void 修复动作_修复人本人_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(10L, "TESTER"), defect(), 2),
                "修复人可流转 →处理中（TC-L2-01）");
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(10L, "TESTER"), defect(), 3),
                "修复人可流转 →已修复");
    }

    @Test
    void 验证动作_报告人本人_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(20L, "TESTER"), defect(), 4),
                "报告人（验证人）可流转 →验证中");
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(20L, "TESTER"), defect(), 5),
                "报告人可流转 →已关闭");
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(20L, "TESTER"), defect(), 6),
                "报告人可流转 →重新打开");
    }

    @Test
    void 非修复人非验证人_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanTransitionDefect(user(30L, "TESTER"), defect(), 2),
                "无关成员执行修复动作 → 403");
        assertEquals(40301, ex.getCode());
        assert (ex.getMessage().contains("修复人或报告人"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> guard.assertCanTransitionDefect(user(30L, "TESTER"), defect(), 5),
                "无关成员执行验证动作 → 403");
        assertEquals(40301, ex2.getCode());
    }

    @Test
    void 角色错位_修复人执行验证动作_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanTransitionDefect(user(10L, "TESTER"), defect(), 5),
                "修复人（非报告人）执行验证动作 → 403");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 项目负责人_任意流转_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(30L, "TEST_LEAD"), defect(), 2),
                "项目负责人可流转任意状态（TC-L2-01 兜底）");
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(30L, "TEST_LEAD"), defect(), 5));
    }

    @Test
    void 系统管理员_任意流转_通过() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanTransitionDefect(user(99L, "ADMIN"), defect(), 3),
                "系统管理员可流转任意状态（PRD 2 章）");
    }

    @Test
    void 开发人员_流转_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanTransitionDefect(user(10L, "DEVELOPER"), defect(), 2),
                "开发角色只读（TC-AUTH-03 语义）");
        assertEquals(40301, ex.getCode());
    }
}
