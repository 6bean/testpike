package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class EnvDeployAccessGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);
    private final EnvDeployController controller = new EnvDeployController(client, guard);

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void 负责人_记录部署_通过() {
        UserContext.set(user("TEST_LEAD"));
        client.memberRoleResult(2);
        controller.recordDeploy(1L, new DeployInfoRequest("v2.0.1"));
    }

    @Test
    void 系统管理员_记录部署_通过() {
        UserContext.set(user("ADMIN"));
        client.memberRoleResult(0);
        controller.recordDeploy(1L, new DeployInfoRequest("v2.0.1"));
    }

    @Test
    void 普通成员_记录部署_403() {
        UserContext.set(user("TESTER"));
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.recordDeploy(1L, new DeployInfoRequest("v2.0.1")),
                "普通成员记录部署 → 403（IT-L6-01：非负责人 403）");
        assertEquals(40301, ex.getCode());
        assertEquals("该操作仅项目负责人可执行", ex.getMessage());
    }

    @Test
    void 非成员_记录部署_403() {
        UserContext.set(user("TESTER"));
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.recordDeploy(1L, new DeployInfoRequest("v2.0.1")),
                "非项目成员记录部署 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 成员_查询部署_通过() {
        UserContext.set(user("TESTER"));
        client.memberRoleResult(1);
        Response<DeployInfoView> resp = controller.getDeployInfo(1L);
        assertEquals("v2.0.1", resp.getData().deployedVersion(), "成员可查询部署状态（TC-L6-01 环境详情展示）");
    }

    @Test
    void 非成员_查询部署_403() {
        UserContext.set(user("TESTER"));
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getDeployInfo(1L));
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 跨项目环境_记录部署_403() {
        UserContext.set(user("TESTER"));
        client.projectIdForEnv(99L);
        client.crossProject(99L);
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.recordDeploy(1L, new DeployInfoRequest("v1.0.0")),
                "项目 1 负责人记录项目 99 的环境 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 跨项目环境_查询_403() {
        UserContext.set(user("TESTER"));
        client.projectIdForEnv(99L);
        client.crossProject(99L);
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getDeployInfo(1L));
        assertEquals(40301, ex.getCode());
    }
}
