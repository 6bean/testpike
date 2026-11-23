package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecChangeAnalysisView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecChangeItemView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeHandleRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeReanalyzeRequest;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreSpecChangeAnalysisClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin bridge contract tests for the contract change-analysis queue
 * (/api/v1/api-spec-change-analyses). Asserted surface: ① list = project member read
 * gate with projectId/status/page/size passed positionally; ② disposition write gate
 * uses the ROW's owning project fetched from core (a client-supplied project id can
 * never authorize); ③ batch disposition gates every checked row's project and rejects
 * before forwarding when one row lacks access; ④ core Info mirrors field-by-field onto
 * the admin view (statusLabel / changes / formatted times must not be silently dropped);
 * ⑤ reanalyze resolves the version's project through the spec row (permission source),
 * never through a queue-row assumption.
 * <p>CoreSpecChangeAnalysisClient is the integration-boundary stand-in (L2 bridge
 * contract isolation); the real admin → core → DB chain is covered by the segment L3
 * live run — verification levels are never substituted.
 */
class ApiSpecChangeAnalysisControllerTest {

    private final CoreSpecChangeAnalysisClient client = mock(CoreSpecChangeAnalysisClient.class);
    private final CoreApiSpecClient specClient = mock(CoreApiSpecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiSpecChangeAnalysisController controller =
            new ApiSpecChangeAnalysisController(client, specClient, guard);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", " tester",
                List.of("TEST_LEAD"), System.currentTimeMillis() + 3600_000L, null));
    }

    private static ApiSpecChangeAnalysisView row(long id, long projectId) {
        return new ApiSpecChangeAnalysisView(id, projectId, 11L, 12L,
                List.of(new ApiSpecChangeItemView("DELETE /pets/{id}", "removed",
                        List.of(21L), List.of(31L), List.of(41L))),
                0, "待复核", "upload", 7L, "2026-10-19 10:00:00", null, null, null);
    }

    private static ApiSpecView specView(long id, long projectId) {
        return new ApiSpecView(id, projectId, null, "pet-api", "OPENAPI", "契约API",
                "sha", 2, List.of(), 11L, null, 7L, "2026-10-19 10:00:00");
    }

    @Test
    void list_uses_member_read_gate_and_passes_params_positionally() {
        login(7L);
        when(client.list(42L, 0, 2, 10)).thenReturn(new PageView<>(List.of(row(1L, 42L)), 1L));
        Response<PageView<ApiSpecChangeAnalysisView>> resp = controller.list(42L, 0, 2, 10);
        assertEquals("0", resp.getCode());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanView(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId());
        assertEquals("待复核", resp.getData().list().get(0).statusLabel());
    }

    @Test
    void handle_permission_source_is_row_project_not_a_client_param() {
        login(7L);
        when(client.get(5L)).thenReturn(row(5L, 42L));
        when(client.handle(5L, "已复核")).thenReturn(row(5L, 42L));

        controller.handle(5L, new SpecChangeHandleRequest(null, "已复核"));
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanWriteRequirement(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        verify(client).handle(5L, "已复核");
    }

    @Test
    void handle_row_missing_core_404_passes_through_before_guard() {
        login(7L);
        when(client.get(9L)).thenThrow(new ServiceException("变更分析记录不存在", 404));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.handle(9L, new SpecChangeHandleRequest(null, "x")));
        assertEquals(404, ex.getCode());
        verify(guard, never()).assertCanWriteRequirement(any(), anyLong());
        verify(client, never()).handle(anyLong(), eq("x"));
    }

    @Test
    void handle_batch_gates_every_rows_project_and_rejects_before_forwarding() {
        login(7L);
        when(client.get(1L)).thenReturn(row(1L, 42L));
        when(client.get(2L)).thenReturn(row(2L, 43L));
        doThrow(new ServiceException("无权限", 403))
                .when(guard).assertCanWriteRequirement(any(), eq(43L));

        assertThrows(ServiceException.class, () -> controller.handleBatch(
                new SpecChangeHandleRequest(List.of(1L, 2L), "批量复核")));
        verify(client, never()).handleBatch(any(), eq("批量复核"));
    }

    @Test
    void handle_batch_forwards_once_all_projects_pass_and_dedupes_projects() {
        login(7L);
        when(client.get(1L)).thenReturn(row(1L, 42L));
        when(client.get(2L)).thenReturn(row(2L, 42L));
        when(client.handleBatch(List.of(1L, 2L), "批量复核"))
                .thenReturn(List.of(row(1L, 42L), row(2L, 42L)));

        Response<List<ApiSpecChangeAnalysisView>> resp = controller.handleBatch(
                new SpecChangeHandleRequest(List.of(1L, 2L), "批量复核"));
        assertEquals("0", resp.getCode());
        assertEquals(2, resp.getData().size());
        assertEquals(List.of(41L), resp.getData().get(0).changes().get(0).mockStubIds());
        verify(guard, times(1)).assertCanWriteRequirement(any(), eq(42L));
        verify(client).handleBatch(List.of(1L, 2L), "批量复核");
    }

    @Test
    void reanalyze_permission_source_is_spec_row_project() {
        login(7L);
        when(specClient.get(12L)).thenReturn(specView(12L, 42L));
        when(client.reanalyze(12L, "poll")).thenReturn(row(5L, 42L));

        controller.reanalyze(new SpecChangeReanalyzeRequest(12L, "poll"));
        verify(guard).assertCanWriteRequirement(any(), eq(42L));
        verify(client).reanalyze(12L, "poll");
    }

    @Test
    void by_spec_read_gate_uses_row_project() {
        login(7L);
        when(client.getBySpec(12L)).thenReturn(row(5L, 42L));
        Response<ApiSpecChangeAnalysisView> resp = controller.getBySpec(12L);
        assertEquals("0", resp.getCode());
        verify(guard).assertCanView(any(), eq(42L));
    }
}
