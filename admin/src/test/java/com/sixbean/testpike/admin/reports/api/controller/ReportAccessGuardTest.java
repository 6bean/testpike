package com.sixbean.testpike.admin.reports.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class ReportAccessGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 覆盖率_项目成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanViewReport(user("TESTER"), 1L, "coverage"),
                "项目内测试人员可查看覆盖率报表");
    }

    @Test
    void 覆盖率_开发成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanViewReport(user("DEVELOPER"), 1L, "coverage"),
                "开发成员按授权范围可查看覆盖率/AI 质量/执行统计（15 文档矩阵）");
    }

    @Test
    void 覆盖率_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanViewReport(user("TESTER"), 1L, "coverage"),
                "非项目成员查看报表 → 403（IT-J3-01，H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 团队看板_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanViewReport(user("TESTER"), 1L, "team_board"),
                "团队看板仅负责人/系统管理员（15 文档矩阵：测试人员 ❌）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 团队看板_项目负责人_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanViewReport(user("TEST_LEAD"), 1L, "team_board"));
    }

    @Test
    void 团队看板_系统管理员_通过() {
        assertDoesNotThrow(() -> guard.assertCanViewReport(user("ADMIN"), 1L, "team_board"));
    }

    @Test
    void 团队看板_开发即使负责人_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanViewReport(user("DEVELOPER"), 1L, "team_board"),
                "开发角色只读（TC-AUTH-03）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 个人进度_开发成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanViewReport(user("DEVELOPER"), 1L, "personal_review"),
                "个人评审进度开发人员 ❌（15 文档矩阵）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 导出_项目成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanExportReport(user("TESTER"), 1L),
                "项目内测试人员可导出（PRD 4.4 导出权限限项目内成员）");
    }

    @Test
    void 导出_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanExportReport(user("TESTER"), 1L),
                "非项目成员导出 → 403（TC-J3-02，H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 导出_开发_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanExportReport(user("DEVELOPER"), 1L),
                "开发人员只读不能导出（15 文档矩阵）");
        assertEquals(40301, ex.getCode());
    }


    @Test
    void 总结报告生成_项目负责人_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanManage(user("TEST_LEAD"), 1L),
                "项目负责人可生成总结报告（TC-L4-01）");
    }

    @Test
    void 总结报告生成_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user("TESTER"), 1L),
                "普通成员生成总结报告 → 403（IT-L4-01）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 总结报告生成_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user("TESTER"), 1L),
                "非项目成员生成总结报告 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 总结报告查看导出_项目成员_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user("TESTER"), 1L),
                "项目内成员可查看/导出报告记录（IT-L4-01）");
    }

    @Test
    void 总结报告查看_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanView(user("TESTER"), 1L),
                "非项目成员查看报告 → 403（IT-L4-01）");
        assertEquals(40301, ex.getCode());
    }
}
