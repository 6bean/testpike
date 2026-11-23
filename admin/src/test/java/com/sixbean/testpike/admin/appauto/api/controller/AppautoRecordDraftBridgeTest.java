package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordDraftView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.admin.appauto.api.dto.RecordDraftConfirmRequest;
import com.sixbean.testpike.admin.appauto.api.dto.RecordFlowGenerateRequest;
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


class AppautoRecordDraftBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoRecordDraftController controller =
            new AppautoRecordDraftController(client, guard);

    private AppautoRecordDraftView draftRow(long id, long projectId, long caseId, int status) {
        return new AppautoRecordDraftView(id, projectId, caseId, 900L, "登录录制", "scrcpy",
                "[{\"stepNo\":1,\"action\":\"app.tap\"}]", status, null, "2026-10-04 03:00:00");
    }

    @Test
    void 发起反推_查看闸与按键透传_应答草稿号步数原样() {
        RecordFlowGenerateRequest request = new RecordFlowGenerateRequest(
                7L, 900L, "mobilewright", "scrcpy", "登录录制",
                List.of(Map.of("type", "tap", "x", 100, "y", 200)));
        when(client.generateRecordFlow(900L, "mobilewright", "scrcpy", "登录录制",
                request.events())).thenReturn(new AppautoRecordFlowView(42L, 3));
        AppautoRecordFlowView got = controller.generate(request).getData();
        assertEquals(42L, got.draftId(), "草稿号原样透传（两层门第一层预览锚）");
        assertEquals(3, got.stepCount(), "步数原样透传");
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 列举_查看闸按项目_异项目行不出桥() {
        when(client.listRecordDrafts(77L, null, 100))
                .thenReturn(List.of(draftRow(1L, 7L, 77L, 1), draftRow(2L, 8L, 77L, 1)));
        List<AppautoRecordDraftView> rows = controller.list(7L, 77L, null, 100).getData();
        verify(guard).assertCanView(any(), eq(7L));
        assertEquals(1, rows.size(), "授权项目外行必须剔除（防他项目 caseId 换越权列表）");
        assertEquals(1L, rows.get(0).id());
    }

    @Test
    void 入库_双闸按core回显归属_勾选步序与版本透传() {
        when(client.findRecordDraft(1L)).thenReturn(draftRow(1L, 7L, 77L, 1));
        when(client.confirmRecordDraft(1L, List.of(1, 2), 4))
                .thenReturn(draftRow(1L, 7L, 77L, 2));
        AppautoRecordDraftView got = controller.confirm(1L,
                new RecordDraftConfirmRequest(List.of(1, 2), 4)).getData();
        assertEquals(2, got.status(), "入库态由 core 回显");
        verify(guard).assertCanManage(any(), eq(7L));
        verify(guard).assertCanWriteCase(any(), eq(7L));
    }

    @Test
    void 驳回_写用例闸按core回显归属_已处理冲突人话上抛不吞() {
        when(client.findRecordDraft(1L)).thenReturn(draftRow(1L, 7L, 77L, 2));
        Mockito.doThrow(new ServiceException("该草稿已处理（已入库），不能重复驳回",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).rejectRecordDraft(1L);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.reject(1L));
        assertTrue(ex.getMessage().contains("已处理"), "core 人话文案禁加工禁吞");
        verify(guard).assertCanWriteCase(any(), eq(7L));
    }

    @Test
    void 单读_非项目成员拒_不吞不越权() {
        when(client.findRecordDraft(1L)).thenReturn(draftRow(1L, 8L, 77L, 1));
        Mockito.doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(8L));
        assertThrows(ServiceException.class, () -> controller.get(1L));
        verify(client, never()).confirmRecordDraft(eq(1L), any(), any());
    }
}
