package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class ProjectListDefaultStatusTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);
    private final ProjectController controller = new ProjectController(client, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    @Test
    void 不传status_默认活跃口径转发() {
        loginAdmin();
        controller.list(1, 10, null, null, null, null);
        assertEquals(1, client.lastListStatus(),
                "T101：不传 status 时 admin 默认 status=1（活跃），归档项目不得混入默认列表");
    }

    @Test
    void 显式status_原样透传() {
        loginAdmin();
        controller.list(1, 10, "kw", 2, null, null);
        assertEquals(2, client.lastListStatus(), "显式 status=2 原样透传（不覆盖）");
    }
}
