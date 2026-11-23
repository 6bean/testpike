package com.sixbean.testpike.admin.explore.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateRequest;
import com.sixbean.testpike.admin.explore.api.dto.ExploreCreateResultView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreDetailView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.admin.explore.infrastructure.remote.client.ExploreCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ExploreBridgeTest {

    private final ExploreCoreClient client = mock(ExploreCoreClient.class);
    private final CoreProjectClient projectClient = mock(CoreProjectClient.class);
    private final ProjectAccessGuard guard = new ProjectAccessGuard(projectClient);
    private final ExploreController controller = new ExploreController(client, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId, String... roles) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null));
    }

    private static ExploreCreateRequest createRequest(Boolean confirm) {
        return new ExploreCreateRequest(7L, "摸清登录流程", "http://localhost:5173", confirm);
    }

 
    @Test
    void confirmMissingReturnsEstimateAndNeverCreates() {
        login(7L, "TESTER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);
        when(client.estimate(eq(7L), any())).thenReturn(
                new com.sixbean.testpike.admin.explore.api.dto.ExploreEstimateView(36000L, 36L, 30));

        Response<ExploreCreateResultView> resp = controller.create(createRequest(null));

        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData().estimate(), "confirm!=TRUE 必返预估");
        assertNull(resp.getData().session(), "预估链路禁返回会话（未落库）");
        verify(client).estimate(eq(7L), any());
        verify(client, never()).create(anyLong(), any(), anyLong());
    }

    
    @Test
    void confirmTrueOwnerCreatesWithOperator() {
        login(7L, "DEVELOPER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(2);
        when(client.create(eq(7L), any(), eq(7L))).thenReturn(new ExploreSessionView(
                1L, 7L, "摸清登录流程", "http://localhost:5173", 1, 0, 0, 0L, null, 7L, "{}"));

        Response<ExploreCreateResultView> resp = controller.create(createRequest(Boolean.TRUE));

        verify(client).create(eq(7L), any(), eq(7L));
        assertNotNull(resp.getData().session(), "创建链路必返会话");
        assertNull(resp.getData().estimate());
    }

    
    @Test
    void nonMemberNonTesterRejectedBeforeCore() {
        login(7L, "DEVELOPER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.create(createRequest(Boolean.TRUE)));

        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(),
                "非成员非 TESTER 应 40301，实际=" + ex.getMessage());
        verifyNoInteractions(client);
    }

    
    @Test
    void plainMemberWithoutTesterRoleRejected() {
        login(7L, "DEVELOPER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.create(createRequest(Boolean.TRUE)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(client);
    }

    
    @Test
    void testerRoleMemberAllowed() {
        login(7L, "TESTER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);
        when(client.create(eq(7L), any(), eq(7L))).thenReturn(new ExploreSessionView(
                1L, 7L, "目标", "http://localhost:5173", 1, 0, 0, 0L, null, 7L, "{}"));

        controller.create(createRequest(Boolean.TRUE));
        verify(client).create(eq(7L), any(), eq(7L));
    }

    
    @Test
    void detailAssemblesSessionAndStepsAfterGuard() {
        login(9L, "TESTER");
        ExploreSessionView session = new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 2, 2, 1, 5L, "报告", 7L, "{}");
        when(client.get(3L)).thenReturn(session);
        when(projectClient.memberRole(7L, 9L)).thenReturn(1);
        when(client.steps(3L)).thenReturn(List.of(new ExploreStepView(
                1, "{\"action\":\"goto\"}", "{\"ok\":true}", null, null, null)));

        Response<ExploreDetailView> resp = controller.detail(3L);

        assertEquals("0", resp.getCode());
        assertEquals(3L, resp.getData().session().id());
        assertEquals(1, resp.getData().steps().size());
        verify(client).get(3L);
        verify(client).steps(3L);
    }

    
    @Test
    void detailNonMemberRejectedBeforeSteps() {
        login(9L, "DEVELOPER");
        ExploreSessionView session = new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 1, 0, 0, 0L, null, 7L, "{}");
        when(client.get(3L)).thenReturn(session);
        when(projectClient.memberRole(7L, 9L)).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.detail(3L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verify(client, never()).steps(anyLong());
    }

    
    @Test
    void coreErrorCodePassesThroughUnchanged() {
        login(9L, "TESTER");
        when(client.get(99L)).thenThrow(
                new ServiceException("探索会话不存在", BizErrorCode.NOT_FOUND));

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.detail(99L));
        assertEquals(40401, ex.getCode(), "core 40401 桥层原样透传");
    }

    
    @Test
    void listUsesMemberGateOnly() {
        login(9L, "DEVELOPER");
        when(projectClient.memberRole(7L, 9L)).thenReturn(1);
        when(client.list(eq(7L), eq(1), eq(20), eq(null), eq(null)))
                .thenReturn(new PageView<>(List.of(), 0L));

        Response<PageView<ExploreSessionView>> resp = controller.list(7L, 1, 20, null, null);
        assertEquals("0", resp.getCode());
        verify(client).list(7L, 1, 20, null, null);
    }

    /** the sort pair is forwarded verbatim — the bridge adds no
 * second whitelist (the fail-closed gate is single-sourced in core), and a
 * non-member never reaches the client even when a sort key is present. */
    @Test
    void listSortPairForwardsWithoutSecondGate() {
        login(9L, "DEVELOPER");
        when(projectClient.memberRole(7L, 9L)).thenReturn(1);
        when(client.list(eq(7L), eq(2), eq(50), eq("goal_text"), eq("asc")))
                .thenReturn(new PageView<>(List.of(), 0L));

        controller.list(7L, 2, 50, "goal_text", "asc");
        verify(client).list(7L, 2, 50, "goal_text", "asc");

        login(9L, "DEVELOPER");
        when(projectClient.memberRole(8L, 9L)).thenReturn(0);
        assertThrows(ServiceException.class, () -> controller.list(8L, 1, 20, "id", "desc"));
        verify(client, never()).list(eq(8L), anyInt(), anyInt(), any(), any());
    }


    
    @Test
    void toCasesOwnerBridgesToCoreWithOperator() {
        login(7L, "DEVELOPER");
        when(client.get(3L)).thenReturn(new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 2, 2, 1, 5L, "报告", 7L, "{}"));
        when(projectClient.memberRole(7L, 7L)).thenReturn(2);
        when(client.toCases(eq(3L), any(), eq(7L))).thenReturn(List.of(101L, 102L));

        Response<List<Long>> resp = controller.toCases(3L,
                new com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest(List.of(1, 2)));

        assertEquals("0", resp.getCode());
        assertEquals(List.of(101L, 102L), resp.getData());
        verify(client).toCases(eq(3L), any(), eq(7L));
    }

    
    @Test
    void toCasesPlainMemberWithoutTesterRejected() {
        login(9L, "DEVELOPER");
        when(client.get(3L)).thenReturn(new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 2, 2, 1, 5L, "报告", 7L, "{}"));
        when(projectClient.memberRole(7L, 9L)).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.toCases(3L,
                        new com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest(List.of(1))));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verify(client, never()).toCases(anyLong(), any(), anyLong());
    }

    
    @Test
    void deleteByCreatorPassesThrough() {
        login(9L, "DEVELOPER");
        when(client.get(3L)).thenReturn(new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 1, 2, 1, 5L, null, 9L, "{}"));
        when(client.cancelSession(3L)).thenReturn(
                new com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView(3L, "cancelled"));

        Response<com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView> resp =
                controller.delete(3L);

        assertEquals("0", resp.getCode());
        assertEquals("cancelled", resp.getData().action());
        verify(client).cancelSession(3L);
    }

    
    @Test
    void deleteByAdminNonCreatorAllowed() {
        login(9L, "ADMIN");
        when(client.get(3L)).thenReturn(new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 2, 2, 1, 5L, "报告", 8L, "{}"));
        when(client.cancelSession(3L)).thenReturn(
                new com.sixbean.testpike.admin.explore.api.dto.ExploreCancelView(3L, "deleted"));

        controller.delete(3L);
        verify(client).cancelSession(3L);
    }

    
    @Test
    void deleteNonCreatorNonAdminRejected() {
        login(9L, "DEVELOPER");
        when(client.get(3L)).thenReturn(new ExploreSessionView(
                3L, 7L, "目标", "http://localhost:5173", 1, 0, 0, 0L, null, 8L, "{}"));
        when(projectClient.memberRole(7L, 9L)).thenReturn(2);

        ServiceException ex = assertThrows(ServiceException.class, () -> controller.delete(3L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verify(client, never()).cancelSession(anyLong());
    }


    
    @Test
    void mobileCreatePassesEnvIdAndCredRefToCoreInput() {
        login(7L, "TESTER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);
        when(client.create(eq(7L), any(), eq(7L))).thenReturn(new ExploreSessionView(
                1L, 7L, "漫游首页", "app://com.demo.app", 1, 0, 0, 0L, null, 7L, "{}", 42L, "cred-key-1"));

        controller.create(new ExploreCreateRequest(
                7L, "漫游首页", "app://com.demo.app", Boolean.TRUE, 42L, "cred-key-1"));

        ArgumentCaptor<com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput> captor =
                ArgumentCaptor.forClass(com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput.class);
        verify(client).create(eq(7L), captor.capture(), eq(7L));
        assertEquals(42L, captor.getValue().envId(), "移动形态 envId 必透传 core");
        assertEquals("cred-key-1", captor.getValue().credRef(), "credRef=凭据引用键名必透传（禁解密值）");
    }

    
    @Test
    void estimatePassesEnvIdAndCredRef() {
        login(7L, "TESTER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);
        when(client.estimate(eq(7L), any())).thenReturn(
                new com.sixbean.testpike.admin.explore.api.dto.ExploreEstimateView(36000L, 36L, 30));

        controller.create(new ExploreCreateRequest(
                7L, "漫游首页", "app://com.demo.app", null, 42L, "cred-key-1"));

        ArgumentCaptor<com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput> captor =
                ArgumentCaptor.forClass(com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput.class);
        verify(client).estimate(eq(7L), captor.capture());
        assertEquals(42L, captor.getValue().envId());
        assertEquals("cred-key-1", captor.getValue().credRef());
    }

    
    @Test
    void webCreateLeavesEnvAndCredNullPassthrough() {
        login(7L, "TESTER");
        when(projectClient.memberRole(7L, 7L)).thenReturn(1);
        when(client.create(eq(7L), any(), eq(7L))).thenReturn(new ExploreSessionView(
                1L, 7L, "摸清登录流程", "http://localhost:5173", 1, 0, 0, 0L, null, 7L, "{}"));

        controller.create(createRequest(Boolean.TRUE));

        ArgumentCaptor<com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput> captor =
                ArgumentCaptor.forClass(com.sixbean.testpike.admin.explore.api.dto.ExploreCreateInput.class);
        verify(client).create(eq(7L), captor.capture(), eq(7L));
        assertNull(captor.getValue().envId(), "web 形态 envId 必为 null（禁桥层臆造默认值）");
        assertNull(captor.getValue().credRef());
    }

    
    @Test
    void detailViewCarriesEnvAndCredRef() {
        login(9L, "TESTER");
        ExploreSessionView session = new ExploreSessionView(
                3L, 7L, "漫游首页", "app://com.demo.app", 2, 2, 1, 5L, "报告", 7L, "{}", 42L, "cred-key-1");
        when(client.get(3L)).thenReturn(session);
        when(projectClient.memberRole(7L, 9L)).thenReturn(1);
        when(client.steps(3L)).thenReturn(List.of());

        Response<ExploreDetailView> resp = controller.detail(3L);

        assertEquals(42L, resp.getData().session().envId());
        assertEquals("cred-key-1", resp.getData().session().credRef());
    }
}
