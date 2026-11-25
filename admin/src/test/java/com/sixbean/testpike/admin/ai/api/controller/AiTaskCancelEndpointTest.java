package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiBatchView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.common.clientobject.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
 * Outward stop door for one AI generation task : the admin
 * face {@code POST /api/v1/ai/tasks/{taskId}/cancel} guards with the same
 * write law the retry door uses (project member and not a read-only
 * developer, resolved from the task's own project — no second permission
 * model), then forwards the single-source stop semantic to core's internal
 * door. The refusal never reaches core, so a stranger cannot settle someone
 * else's task; an unknown id still forwards because core answers NOT_FOUND
 * from its own row check (same pass-through posture as retry).
 * <p>The batch progress view additionally carries core's separate
 * {@code cancelled} bucket through the wire — a stopped item must not fold
 * into {@code failed} when the front-end drawer counts outcomes.
 * <p>L2 isolation on the transport doors (core clients / guard); the
 * controller's gate ordering and the JSON name alignment run for real.
 */
class AiTaskCancelEndpointTest {

    private final StreamDoorTestAssembly wiring = new StreamDoorTestAssembly();

    @AfterEach
    void tearDown() {
        wiring.clearLogin();
    }

    private AiTaskView task(long id, long projectId) {
        return new AiTaskView(id, projectId, 5L, 2, 2, "重新生成脚本", null, 8L, null,
                null, null, 0, "2026-11-12T10:00:00", "2026-11-12T10:01:00");
    }

    @Test
    void stopDoorGuardsOnTheTasksOwningProjectThenForwardsOnce() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(7L)).thenReturn(task(7, 42));

        Response<Void> resp = wiring.aiController.cancel(7L);

        verify(wiring.guard).assertCanWriteCase(any(CurrentUser.class), eq(42L));
        verify(wiring.taskClient).cancelTask(7L);
        assertEquals("0", resp.getCode());
    }

    @Test
    void writeGateRefusalStopsBeforeAnyForwardReachesCore() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(9L)).thenReturn(task(9, 42));
        doThrow(new IllegalStateException("开发人员为只读角色，无法执行该操作"))
                .when(wiring.guard).assertCanWriteCase(any(CurrentUser.class), eq(42L));

        assertThrows(IllegalStateException.class, () -> wiring.aiController.cancel(9L));
        verify(wiring.taskClient, never()).cancelTask(anyLong());
    }

    @Test
    void unknownTaskKeepsTheRetryPassThroughPostureWithoutAnOwnershipProbe() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(404L)).thenReturn(null);

        wiring.aiController.cancel(404L);

        verify(wiring.guard, never()).assertCanWriteCase(any(), anyLong());
        verify(wiring.taskClient).cancelTask(404L);
    }

    @Test
    void batchViewCarriesTheSeparateCancelledBucketFromTheCoreWire() throws Exception {
        // Core AiBatchInfo serialises cancelled as its own bucket; the admin
        // record must bind the same JSON name or the count silently drops to
        // zero on the drawer (unknown-property lenient mapping).
        String coreWire = "{\"id\":3,\"projectId\":42,\"operatorId\":5,\"total\":10,"
                + "\"pending\":0,\"running\":0,\"done\":6,\"failed\":1,\"cancelled\":3,"
                + "\"createdAt\":\"2026-11-12T10:00:00\",\"items\":[]}";

        AiBatchView view = new ObjectMapper().readValue(coreWire, AiBatchView.class);

        assertEquals(3, view.cancelled());
        assertEquals(6, view.done());
        assertEquals(1, view.failed());
    }

    @Test
    void batchDoorEchoesTheCancelledCountToTheViewer() {
        wiring.loginMember();
        when(wiring.batchClient.getBatch(3L)).thenReturn(new AiBatchView(3L, 42L, 5L, 10,
                0, 0, 6, 1, 3, "2026-11-12T10:00:00", List.of(), null));

        Response<AiBatchView> resp = wiring.aiController.getBatch(3L);

        verify(wiring.guard).assertCanView(any(CurrentUser.class), eq(42L));
        assertEquals(3, resp.getData().cancelled());
    }
}
