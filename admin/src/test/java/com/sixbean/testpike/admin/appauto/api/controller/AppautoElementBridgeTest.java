package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementCaptureInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Element library admin bridge contract test (P1 .
 * <p>Asserted surface: ① listing stays inside the viewed project (view gate on
 * the requested project id, paged result passed through untouched); ② single
 * read authorizes against the owner echoed by core, never against a client
 * claim; ③ create gates on the manage role for the target project; ④ update
 * and delete re-resolve ownership from core before the manage gate (request
 * body carries no project claim); ⑤ core human-readable rejections (missing
 * row, duplicate name, conflict) pass through unmutated — the bridge must not
 * add a second validation layer.
 * <p>CoreAppautoClient double = bridge-contract L2 isolation (same posture as
 * AppautoPopupRuleBridgeTest); the real admin-to-core chain is covered by L3.
 */
class AppautoElementBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoElementController controller =
            new AppautoElementController(client, guard);

    private AppautoElementView row(long id, long projectId) {
        return new AppautoElementView(id, projectId, "login-button", "IMAGE",
                "{\"artifactKey\":\"el/1.png\"}", "[]", 0.85,
                "2026-11-10 01:00:00", "2026-11-10 01:00:00");
    }

    private AppautoElementInput input() {
        return new AppautoElementInput("login-button", "IMAGE",
                Map.of("artifactKey", "el/1.png"), List.of(), 0.85);
    }

    private AppautoElementCaptureInput captureInput() {
        return new AppautoElementCaptureInput(42L, 0, "login-button",
                10, 20, 100, 80, List.of(1.0, 0.8), 0.85);
    }

    @Test
    void capture_gatesManageOnTargetProject_andReturnsCoreFiledElementId() {
        when(client.captureElement(eq(7L), any(AppautoElementCaptureInput.class)))
                .thenReturn(55L);
        long id = controller.capture(7L, captureInput()).getData();
        verify(guard).assertCanManage(any(), eq(7L));
        verify(client).captureElement(eq(7L), any(AppautoElementCaptureInput.class));
        assertEquals(55L, id, "the element id core issued is what the operator sees");
    }

    @Test
    void capture_deniedByManageGate_neverCallsCore() {
        Mockito.doThrow(new ServiceException("无权管理该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanManage(any(), eq(7L));
        assertThrows(ServiceException.class, () -> controller.capture(7L, captureInput()));
        verify(client, never()).captureElement(Mockito.anyLong(),
                any(AppautoElementCaptureInput.class));
    }

    @Test
    void page_gatesViewOnRequestedProject_andPassesPagedResultThrough() {
        when(client.pageElements(7L, "login", "IMAGE", 1, 20))
                .thenReturn(new AppautoElementPageView(List.of(row(1L, 7L)), 1L));
        AppautoElementPageView got = controller.page(7L, "login", "IMAGE", 1, 20).getData();
        verify(guard).assertCanView(any(), eq(7L));
        assertEquals(1L, got.total(), "total travels as core computed it");
        assertEquals(1L, got.list().get(0).id());
    }

    @Test
    void find_deniedWhenCoreEchoedOwnerIsOutsideAccess() {
        when(client.findElement(1L)).thenReturn(row(1L, 8L));
        Mockito.doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(8L));
        assertThrows(ServiceException.class, () -> controller.get(1L));
        verify(client, never()).deleteElement(1L);
    }

    @Test
    void create_requiresManageGateOnTargetProject_andReturnsNewId() {
        when(client.createElement(eq(7L), any(AppautoElementInput.class))).thenReturn(42L);
        long id = controller.create(7L, input()).getData();
        assertEquals(42L, id, "new row id passes through (page re-selects by it)");
        verify(guard).assertCanManage(any(), eq(7L));
    }

    @Test
    void update_authorizesAgainstCoreEchoedOwner_notRequestBody() {
        when(client.findElement(1L)).thenReturn(row(1L, 7L));
        when(client.updateElement(eq(1L), any(AppautoElementInput.class)))
                .thenReturn(row(1L, 7L));
        AppautoElementView got = controller.update(1L, input()).getData();
        verify(guard).assertCanManage(any(), eq(7L));
        assertEquals("login-button", got.name());
    }

    @Test
    void delete_coreConflictWordingPassesThroughUnmutated() {
        when(client.findElement(1L)).thenReturn(row(1L, 7L));
        Mockito.doThrow(new ServiceException("本项目已存在同名元素「login-button」，请更换名称或编辑现有元素",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).deleteElement(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.delete(1L));
        assertTrue(ex.getMessage().contains("同名元素"), "core wording must not be reworded");
        verify(guard).assertCanManage(any(), eq(7L));
    }
}
