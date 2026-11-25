package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameWriter;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.common.clientobject.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outward doors of the step-generation task's process-event ledger
 * : the operator's tail drawer reads one AI task's
 * live per-shard events over the same two postures the extraction tail
 * already uses — a plain cursor window for the poll fallback, and an SSE
 * stream whose pump loop is the shared single source
 * ({@link StepsGenEventStreamBridge}, which rides the shared
 * ProcessEventPump). Both doors are read-only observation faces: the
 * ownership gate is the existing project-member check resolved from the
 * task's own project (no second permission model), and the stream door
 * refuses a non-member before any tail thread is started.
 * <p>L2 isolation on the transport doors (core clients / guard / bridge);
 * the controller's gate ordering and header posture run for real.
 */
class StepsGenTaskEventsEndpointTest {

    private final StreamDoorTestAssembly wiring = new StreamDoorTestAssembly();

    @AfterEach
    void tearDown() {
        wiring.clearLogin();
    }

    private AiTaskView task(long id, long projectId) {
        return new AiTaskView(id, projectId, 5L, 2, 2, "重新生成脚本", null, 8L, null,
                null, null, 0, "2026-11-11T10:00:00", "2026-11-11T10:01:00");
    }

    @Test
    void cursorWindowDoorSlicesByTheTaskOwningProjectAndEchoesLedgerWindow() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(7L)).thenReturn(task(7, 42));
        when(wiring.taskClient.taskProcessEvents(7L, 3L, 50)).thenReturn(List.of(
                new AiTaskEventView(4L, "step", "第1/2片完成", null, "2026-11-11T10:02:00")));

        Response<List<AiTaskEventView>> resp = wiring.aiController.taskProcessEvents(7L, 3L, 50);

        verify(wiring.guard).assertCanView(any(CurrentUser.class), eq(42L));
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        assertEquals(4L, resp.getData().get(0).cursor());
        assertEquals("第1/2片完成", resp.getData().get(0).summary());
    }

    @Test
    void streamDoorSealsBufferingHeadersAndHandsOffToTheSharedPump() throws Exception {
        wiring.loginMember();
        when(wiring.taskClient.getTask(8L)).thenReturn(task(8, 42));
        CountDownLatch tailStarted = new CountDownLatch(1);
        doAnswer(call -> {
            tailStarted.countDown();
            return null;
        }).when(wiring.stepsBridge).stream(eq(8L), eq(0L), any(StreamFrameWriter.class));

        HttpServletResponse http = mock(HttpServletResponse.class);
        SseEmitter emitter = wiring.aiController.taskProcessEventStream(8L, 0L, http);

        assertNotNull(emitter);
        assertTrue(tailStarted.await(2, TimeUnit.SECONDS),
                "the door must hand the cursor off to the shared tail pump");
        verify(wiring.guard).assertCanView(any(CurrentUser.class), eq(42L));
        verify(http).setHeader("X-Accel-Buffering", "no");
        verify(http).setHeader("Cache-Control", "no-cache");
    }

    @Test
    void streamDoorRefusesANonMemberBeforeStartingAnyTail() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(9L)).thenReturn(task(9, 42));
        doThrow(new IllegalStateException("forbidden project view"))
                .when(wiring.guard).assertCanView(any(CurrentUser.class), eq(42L));

        assertThrows(IllegalStateException.class,
                () -> wiring.aiController.taskProcessEventStream(9L, 0L, mock(HttpServletResponse.class)));
        verify(wiring.stepsBridge, never()).stream(anyLong(), anyLong(), any());
    }

    @Test
    void missingTaskKeepsTheOldEchoShapeWithoutAnOwnershipProbe() {
        wiring.loginMember();
        when(wiring.taskClient.getTask(404L)).thenReturn(null);
        when(wiring.taskClient.taskProcessEvents(404L, 0L, null)).thenReturn(List.of());

        Response<List<AiTaskEventView>> resp = wiring.aiController.taskProcessEvents(404L, 0L, null);

        verify(wiring.guard, never()).assertCanView(any(), anyLong());
        assertTrue(resp.getData().isEmpty());
    }
}
