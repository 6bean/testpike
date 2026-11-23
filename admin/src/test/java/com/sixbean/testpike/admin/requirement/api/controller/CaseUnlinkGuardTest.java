package com.sixbean.testpike.admin.requirement.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.StubCoreCaseClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.requirement.infrastructure.remote.client.StubCoreRequirementClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CaseUnlinkGuardTest {

    private static final long CASE_ID = 100L;
    private static final long REQ_ID = 200L;
    private static final long PROJECT_ID = 7L;

    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(projectClient);
    private final StubCoreRequirementClient requirementClient = new StubCoreRequirementClient();
    private final StubCoreCaseClient caseClient = new StubCoreCaseClient();
    private final RequirementController controller =
            new RequirementController(requirementClient, caseClient, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void login(String... roles) {
        UserContext.set(new CurrentUser(1L, "u1", "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null));
    }

    
    @Test
    void unlink_关联存在_同源转发core() {
        caseClient.withCase(CASE_ID, PROJECT_ID);
        requirementClient.withLinkedRequirements(StubCoreRequirementClient.reqView(REQ_ID, PROJECT_ID, 1));
        login("ADMIN");

        long result = controller.unlinkCaseFromCase(CASE_ID, REQ_ID).getData();

        assertEquals(REQ_ID, result);
        long[] unlink = requirementClient.lastUnlink();
        assertEquals(REQ_ID, unlink[0], "同源转发 core：requirementId 位不变（禁 admin 自实现删除）");
        assertEquals(CASE_ID, unlink[1], "同源转发 core：caseId 位不变");
    }

 
    @Test
    void unlink_无关联_404人话() {
        caseClient.withCase(CASE_ID, PROJECT_ID);
        requirementClient.withLinkedRequirements();
        login("ADMIN");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.unlinkCaseFromCase(CASE_ID, REQ_ID));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("关联"), "提示人话含「关联」: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("用例"), "提示人话含「用例」: " + ex.getMessage());
    }

 
    @Test
    void unlink_跨项目_403() {
        caseClient.withCase(CASE_ID, PROJECT_ID);
        projectClient.memberRoleResult(0);
        projectClient.crossProject(PROJECT_ID);
        login("TESTER");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.unlinkCaseFromCase(CASE_ID, REQ_ID));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
    }

    
    @Test
    void unlink_开发人员_403() {
        caseClient.withCase(CASE_ID, PROJECT_ID);
        projectClient.memberRoleResult(1);
        login("DEVELOPER");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.unlinkCaseFromCase(CASE_ID, REQ_ID));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
    }
}
