package com.sixbean.testpike.admin.explore.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.admin.explore.infrastructure.remote.client.ExploreCoreClient;
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
 * R62 A-face bridge contract for the explore process-event tail door. The
 * bridge owns exactly one decision: the owning project is read server-side
 * from the session row and guarded before any ledger row is forwarded, so a
 * non-member cannot tail another project's exploration. Row shape and cursor
 * semantics are core's single source (the bridge passes them through unchanged
 * — no explore-specific viewer model).
 * <p>ExploreCoreClient / ProjectAccessGuard are integration-boundary doubles,
 * mirroring ExploreBridgeTest; the real admin→core→DB chain is covered by the
 * live E2E tail segment.
 */
class ExploreProcessEventsBridgeTest {

    private static final long SESSION_ID = 8801L;
    private static final long PROJECT_ID = 42L;

    private final ExploreCoreClient client = mock(ExploreCoreClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ExploreController controller = new ExploreController(client, guard);

    /** Real admin mirror row (records are final; the bridge reads projectId only). */
    private ExploreSessionView sessionRow() {
        return new ExploreSessionView(SESSION_ID, PROJECT_ID, "自由探索",
                "http://localhost:5173/orders", 1, 3, 2, 42L, null, 1L, "{}");
    }

    @Test
    void membershipIsGuardedByTheServerSideProjectBeforeAnyRowIsForwarded() {
        ExploreSessionView session = sessionRow();
        when(client.get(SESSION_ID)).thenReturn(session);
        List<AiTaskEventView> tail = List.of(
                new AiTaskEventView(1L, "step", "探索开始｜目标 自由探索", null, "2026-11-18T23:00:00"));
        when(client.processEvents(SESSION_ID, 7L, 200)).thenReturn(tail);

        Response<List<AiTaskEventView>> answer = controller.processEvents(SESSION_ID, 7L, 200);

        verify(guard).assertCanView(any(), eq(PROJECT_ID));
        assertEquals(tail, answer.getData(), "行集原样透传，桥层不改写游标与级别");
    }

    @Test
    void refusedMembershipNeverTouchesTheProcessLedger() {
        when(client.get(SESSION_ID)).thenReturn(sessionRow());
        doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), anyLong());

        assertThrows(ServiceException.class, () -> controller.processEvents(SESSION_ID, 0L, null));
        verify(client, never()).processEvents(anyLong(), anyLong(), any());
    }
}
