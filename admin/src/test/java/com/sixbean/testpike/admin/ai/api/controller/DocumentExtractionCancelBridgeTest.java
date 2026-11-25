package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * cancel bridge (L2): POST /api/v1/ai/documents/{id}/extraction-cancel hands
 * an explicit user cancel to the core internal face under the same write door as
 * upload/extract (assertCanWriteRequirement). Asserted here:
 * 1) the permission source is the document's owning project (fetch the row first,
 * then gate — same order as extract, never the caller's word);
 * 2) a refused write door short-circuits: core is never called;
 * 3) core's honest rejection ("nothing is extracting") propagates as-is, the bridge
 * must not swallow it into a fake success;
 * 4) the settled document row passes through without silent loss.
 * The real admin→core→DB chain is covered by the batch-close L3 suite (layering rule).
 */
class DocumentExtractionCancelBridgeTest {

    private final StreamDoorTestAssembly wiring = new StreamDoorTestAssembly();

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    private static DocumentInfoView docFixture(long docId, long projectId, int status, String error) {
        return new DocumentInfoView(docId, projectId, "PRD-v2.md", "markdown",
                "hash", 2048L, "k", status, 12, error, null, true, true, true, 0, null, null, null);
    }

    @Test
    void cancel_gatesOnOwningProjectThenDelegatesToCore() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L, 3, null));
        when(wiring.coreDocumentClient.cancelExtraction(12L))
                .thenReturn(docFixture(12L, 42L, 1, "提取已由用户取消"));

        Response<DocumentInfoView> resp = wiring.documentController.cancelExtraction(12L);

        assertEquals("0", resp.getCode());
        verify(wiring.guard).assertCanWriteRequirement(any(CurrentUser.class), eq(42L));
        assertEquals(1, resp.getData().status(), "取消后行态=待提取(1)，由 core 落定");
        assertEquals("提取已由用户取消", resp.getData().errorMsg(), "取消人话原样透传");
    }

    @Test
    void cancel_writeDoorRefused_coreNeverCalled() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L, 3, null));
        org.mockito.Mockito.doThrow(new ServiceException("无权限操作该文档", 403))
                .when(wiring.guard).assertCanWriteRequirement(any(CurrentUser.class), eq(42L));

        assertThrows(ServiceException.class, () -> wiring.documentController.cancelExtraction(12L));
        verify(wiring.coreDocumentClient, never()).cancelExtraction(12L);
    }

    @Test
    void cancel_honestRejectionFromCore_propagates() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(wiring.coreDocumentClient.get(12L)).thenReturn(docFixture(12L, 42L, 4, null));
        when(wiring.coreDocumentClient.cancelExtraction(12L))
                .thenThrow(new ServiceException("当前没有进行中的提取任务，无需取消", 409));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> wiring.documentController.cancelExtraction(12L));
        assertEquals("当前没有进行中的提取任务，无需取消", ex.getMessage(),
                "无事可取消的拒止人话不得被桥吞成假成功");
    }
}
