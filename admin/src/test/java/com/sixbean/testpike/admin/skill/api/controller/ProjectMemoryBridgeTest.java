package com.sixbean.testpike.admin.skill.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.ProjectMemoryClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * project-memory ledger admin bridge contract test (02 §2.16
 * /api/v1/project-memory six doors → core /internal/project-memory).
 * <p>Assertion faces: ①every call forwards its project segment to core — the
 * bridge never relays a caller-chosen id without the projectId scope, so the
 * core ownership single-source can reject cross-project ids (a foreign entry
 * simply never matches and surfaces as the core notFound human message);
 * ②governance writes require the project-lead role (ADMIN exempt) BEFORE any
 * core hop — a refused call must not touch the client (403 never reaches
 * core, SkillAccessGuard.assertCanManage reused as-is, no new guard);
 * ③read doors require project membership (assertCanView, H1 gate) first;
 * ④ProjectMemoryView mirrors the core ProjectMemoryInfo eight fields name for
 * name — a dropped hitCount/sourceRef silently empties the ledger columns
 * (the BeanProperty silent-skip defense line, same shape).
 * <p>ProjectMemoryClient is the integration-boundary stand-in (guard/bridge
 * contract L2 isolation); the live admin→core chain is covered by the
 * closing L3 spectrum (verification layers never skip a step).
 */
class ProjectMemoryBridgeTest {

    private final ProjectMemoryClient client = mock(ProjectMemoryClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final SkillAccessGuard skillGuard = new SkillAccessGuard(projectGuard);
    private final ProjectMemoryController controller =
            new ProjectMemoryController(client, projectGuard, skillGuard);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId, String... roles) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    private static ProjectMemoryView row(long id, Long projectId) {
        return new ProjectMemoryView(id, projectId, "登录断言统一走 page.login",
                "[{\"type\":\"review-reject\",\"id\":9}]", 1, 3L,
                LocalDateTime.of(2026, 11, 1, 9, 0), LocalDateTime.of(2026, 11, 2, 9, 0),
                null);
    }

    @Test
    void listViewMirrorsCoreInfoEightFieldsWithoutSilentDrop() throws Exception {
        // Field-name alignment gate (cross-service deserialization matches by
        // name): a plain sourceRef value proves the whole field travels — the
        // nested-JSON quoting is internal to the core column, not this gate.
        String coreJson = """
                {"id":12,"projectId":3,"content":"接口断言禁止只判 200",\
                "sourceRef":"thumbs-down:41",\
                "injectStatus":0,"hitCount":7,"createdAt":"2026-11-01T08:30:00",\
                "updatedAt":"2026-11-02T07:10:00"}""";
        ProjectMemoryView view = mapper.readValue(coreJson, ProjectMemoryView.class);
        assertEquals(12L, view.id());
        assertEquals(3L, view.projectId());
        assertEquals("接口断言禁止只判 200", view.content());
        assertEquals("thumbs-down:41", view.sourceRef());
        assertEquals(0, view.injectStatus());
        assertEquals(7L, view.hitCount());
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 30), view.createdAt());
        assertEquals(LocalDateTime.of(2026, 11, 2, 7, 10), view.updatedAt());
    }

    @Test
    void listForwardsProjectSliceKeywordStateAndPagingToCore() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(1);
        when(client.list(3L, "断言", 0, 2, 10))
                .thenReturn(new PageView<>(List.of(row(12L, 3L)), 1L));
        PageView<ProjectMemoryView> page = controller.list(3L, "断言", 0, 2, 10).getData();
        assertEquals(1L, page.total());
        assertEquals(12L, page.list().get(0).id());
        verify(client).list(3L, "断言", 0, 2, 10);
    }

    @Test
    void nonMemberListIsRefusedBeforeAnyCoreHop() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.list(3L, null, null, 1, 20));
        assertEquals(40301, ex.getCode());
        verify(client, never()).list(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void recycleBinReadRequiresMembershipAndForwardsPaging() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> controller.recycleBin(3L, 1, 20));
        projectClient.memberRoleResult(1);
        when(client.recycleBin(3L, 1, 20)).thenReturn(new PageView<>(List.of(), 0L));
        assertEquals(0L, controller.recycleBin(3L, 1, 20).getData().total());
        verify(client).recycleBin(3L, 1, 20);
    }

    @Test
    void plainMemberCreateIsRefusedLeadOnlyGate() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.create(new ProjectMemoryCreateRequest(3L, "新约定")));
        assertEquals(40301, ex.getCode());
        verify(client, never()).create(any(ProjectMemoryCreateRequest.class));
    }

    @Test
    void leadCreateForwardsPayloadAndReturnsLedgerRow() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(2);
        when(client.create(any(ProjectMemoryCreateRequest.class))).thenReturn(row(12L, 3L));
        ProjectMemoryView view = controller.create(new ProjectMemoryCreateRequest(3L, "新约定")).getData();
        assertEquals(12L, view.id());
        verify(client).create(new ProjectMemoryCreateRequest(3L, "新约定"));
    }

    @Test
    void statusChangeCarriesProjectSliceAndRequiresLead() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(1);
        assertThrows(ServiceException.class, () -> controller.setStatus(12L, 3L, 0));
        verify(client, never()).setInjectStatus(anyLong(), anyLong(), anyInt());
        projectClient.memberRoleResult(2);
        // The disabled echo carries the disabled row (row() defaults to the
        // effective state, so build the 0-state shape core would return).
        when(client.setInjectStatus(12L, 3L, 0)).thenReturn(new ProjectMemoryView(12L, 3L,
                "登录断言统一走 page.login", "[]", 0, 3L,
                LocalDateTime.of(2026, 11, 1, 9, 0), LocalDateTime.of(2026, 11, 2, 9, 0),
                null));
        assertEquals(0, controller.setStatus(12L, 3L, 0).getData().injectStatus());
    }

    @Test
    void deleteAndRestoreAreLeadGatedProjectScopedPassThroughs() {
        login(42L, "DEVELOPER");
        projectClient.memberRoleResult(1);
        assertThrows(ServiceException.class, () -> controller.delete(12L, 3L));
        assertThrows(ServiceException.class, () -> controller.restore(12L, 3L));
        verify(client, never()).delete(anyLong(), anyLong());
        verify(client, never()).restore(anyLong(), anyLong());
        projectClient.memberRoleResult(2);
        controller.delete(12L, 3L);
        when(client.restore(12L, 3L)).thenReturn(row(12L, 3L));
        assertEquals(12L, controller.restore(12L, 3L).getData().id());
        verify(client).delete(12L, 3L);
        verify(client).restore(12L, 3L);
    }

    @Test
    void adminExemptFromLeadGateOnGovernanceWrites() {
        login(9L, "ADMIN");
        controller.delete(12L, 3L);
        verify(client).delete(12L, 3L);
    }
}
