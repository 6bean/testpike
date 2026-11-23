package com.sixbean.testpike.admin.ai.app.service;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.api.dto.AiTaskView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiTaskClient;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sixbean.testpike.admin.aichat.app.component.StreamPumpConfigDoors.fastConfigDoor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2 spectra of the steps-generation tail-f pump : the
 * shared {@link ProcessEventPump} single-sources the loop both the document
 * extraction drawer and the future step-group drawer ride, so this suite
 * proves the second consumer keeps the same contract — events forwarded in
 * strictly increasing cursor order, the stream seals once the task leaves the
 * running states (pending/running) with one final drain, and an unreachable
 * door ends the stream with one honest error frame naming the subject.
 * <p>Infrastructure isolation only (L2 rule): the core task door and the K8
 * config door are faked; the real admin→core chain is proven by the L3 pass.
 */
class StepsGenEventStreamBridgeTest {

    private static AiTaskEventView row(long cursor, String level, String summary, String detail) {
        return new AiTaskEventView(cursor, level, summary, detail, "2026-11-11T10:00:00");
    }

    private static AiTaskView task(int status) {
        return new AiTaskView(9L, 1L, 1L, 2, status, null, null, null, null,
                null, null, 0, null, null);
    }

    @Test
    void forwardsLiveEventsInOrderAndSealsAfterFinalDrain() {
        CoreAiTaskClient core = mock(CoreAiTaskClient.class);
        when(core.taskProcessEvents(eq(9L), eq(0L), any())).thenReturn(List.of(
                row(1, "step", "拆片启动", "{\"stageCode\":\"start\"}"),
                row(2, "thinking", "片1 完成", "{\"promptTokens\":10}")));
        when(core.taskProcessEvents(eq(9L), eq(2L), any())).thenReturn(List.of(
                row(3, "step", "合并完成", "{}")));
        when(core.taskProcessEvents(eq(9L), eq(3L), any())).thenReturn(List.of());
        when(core.getTask(9L)).thenReturn(task(2), task(3), task(3), task(3));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new StepsGenEventStreamBridge(core, fastConfigDoor()).stream(9L, 0L, writer);

        assertEquals(List.of("step", "thinking", "step"), writer.frameTypes);
        assertEquals(List.of(1L, 2L, 3L), writer.seqs);
        assertEquals("片1 完成", writer.payloads.get(1));
        assertTrue(writer.completed, "a settled task must seal the stream");
    }

    @Test
    void sealsImmediatelyWhenTaskAlreadySettled() {
        CoreAiTaskClient core = mock(CoreAiTaskClient.class);
        when(core.taskProcessEvents(eq(9L), eq(0L), any())).thenReturn(List.of());
        when(core.getTask(9L)).thenReturn(task(3));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new StepsGenEventStreamBridge(core, fastConfigDoor()).stream(9L, 0L, writer);

        assertTrue(writer.frameTypes.isEmpty(), "no event frames for a settled run");
        assertTrue(writer.completed, "a settled task must not park the stream");
    }

    @Test
    void windowDoorOutageEndsWithOneHonestErrorFrame() {
        CoreAiTaskClient core = mock(CoreAiTaskClient.class);
        when(core.taskProcessEvents(eq(9L), eq(0L), any()))
                .thenThrow(new RuntimeException("core 门不可达"));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new StepsGenEventStreamBridge(core, fastConfigDoor()).stream(9L, 0L, writer);

        assertEquals(List.of("error"), writer.frameTypes);
        assertTrue(writer.payloads.get(0).contains("生成"),
                "the failure frame must name which job face was unreadable");
        assertFalse(writer.payloads.get(0).contains("步骤生成"),
                "the level chip already says 步骤; the sentence must not repeat it");
        assertTrue(writer.completed);
    }

    @Test
    void probeDoorOutageEndsWithOneHonestErrorFrame() {
        CoreAiTaskClient core = mock(CoreAiTaskClient.class);
        when(core.taskProcessEvents(eq(9L), anyLong(), any())).thenReturn(List.of());
        when(core.getTask(9L)).thenThrow(new RuntimeException("状态门不可达"));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new StepsGenEventStreamBridge(core, fastConfigDoor()).stream(9L, 0L, writer);

        assertEquals(List.of("error"), writer.frameTypes);
        assertTrue(writer.payloads.get(0).contains("任务状态"),
                "the failure frame must name the state probe as the cause");
        assertTrue(writer.completed);
    }
}
