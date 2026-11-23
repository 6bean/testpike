package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.infrastructure.remote.client.CoreBatchClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R62 B-face bridge contract for the batch process-event tail door. The
 * bridge owns exactly one decision: the owning project is read server-side
 * from the batch row and guarded before any ledger row is forwarded, so a
 * non-member cannot tail another project's batch execution. Row shape and
 * cursor semantics are core's single source (the bridge passes them through
 * unchanged — no batch-specific viewer model).
 * <p>CoreBatchClient / ProjectAccessGuard are integration-boundary doubles,
 * mirroring ExploreProcessEventsBridgeTest; the real admin→core→DB chain is
 * covered by the live E2E tail segment.
 */
class BatchProcessEventsBridgeTest {

    private static final long BATCH_ID = 6601L;
    private static final long PROJECT_ID = 42L;

    private final CoreBatchClient client = mock(CoreBatchClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final BatchController controller = new BatchController(client, guard);

    /** Real admin mirror row (the bridge reads projectId only). */
    private BatchInfoView batchRow() {
        return new BatchInfoView(BATCH_ID, PROJECT_ID, "B-6601", "nightly regression", 7L,
                "dev", 1, "执行中", 1, null, 30L, null, 1L, "2026-11-19T01:00:00",
                "2026-11-19T01:05:00", null, 3, null, 0, null, "chromium", 0, null);
    }

    @Test
    void membershipIsGuardedByTheServerSideProjectBeforeAnyRowIsForwarded() {
        when(client.get(BATCH_ID)).thenReturn(batchRow());
        List<AiTaskEventView> tail = List.of(
                new AiTaskEventView(1L, "step", "批次开始｜用例 30", null, "2026-11-19T01:05:00"));
        when(client.processEvents(BATCH_ID, 7L, 200)).thenReturn(tail);

        Response<List<AiTaskEventView>> answer = controller.processEvents(BATCH_ID, 7L, 200);

        verify(guard).assertCanView(any(), eq(PROJECT_ID));
        assertEquals(tail, answer.getData(), "row set passes through unchanged — the bridge never rewrites the cursor or level");
    }

    @Test
    void refusedMembershipNeverTouchesTheProcessLedger() {
        when(client.get(BATCH_ID)).thenReturn(batchRow());
        doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), anyLong());

        assertThrows(ServiceException.class, () -> controller.processEvents(BATCH_ID, 0L, null));
        verify(client, never()).processEvents(anyLong(), anyLong(), any());
    }
}
