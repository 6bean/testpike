package com.sixbean.testpike.admin.ai.app.service;

import com.sixbean.testpike.admin.ai.api.dto.ExtractionEventView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionPreviewView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.sixbean.testpike.admin.aichat.app.component.StreamPumpConfigDoors.fastConfigDoor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 spectra of the extraction tail-f pump : a live window is
 * forwarded frame-by-frame in strictly increasing cursor order; the stream
 * seals once the document leaves the extracting state and the final events
 * are drained; silence emits heartbeat frames; an unreachable event door ends
 * with one honest error frame; every pull asks core from the last cursor the
 * viewer saw (never rewinding, never replaying delivered rows).
 * <p>Infrastructure isolation only (L2 rule): the core door and the K8 config
 * door are faked; the real admin→core chain is proven by the L3 live pass.
 */
class ExtractionEventStreamBridgeTest {

    private static ExtractionEventView row(long cursor, String level, String summary, String detail) {
        return new ExtractionEventView(cursor, level, summary, detail, "2026-11-09T10:00:00");
    }

    private static ExtractionPreviewView preview(int status) {
        return new ExtractionPreviewView(7L, status, null, 1, 1, 0, List.of(),
                null, 0, List.of(), List.of(), List.of(), null, null, List.of());
    }

    @Test
    void forwardsLiveEventsInOrderAndSealsAfterFinalDrain() {
        CoreDocumentClient core = mock(CoreDocumentClient.class);
        when(core.extractionEvents(eq(7L), eq(0L), any())).thenReturn(List.of(
                row(1, "step", "提取启动", "{\"stageCode\":\"start\"}"),
                row(2, "thinking", "桶1 完成", "{\"promptTokens\":10}")));
        when(core.extractionEvents(eq(7L), eq(2L), any())).thenReturn(List.of(
                row(3, "step", "分组完成", "{}")));
        when(core.extractionEvents(eq(7L), eq(3L), any())).thenReturn(List.of());
        when(core.extraction(anyLong(), eq(1), eq(1)))
                .thenReturn(preview(3), preview(4), preview(4), preview(4));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new ExtractionEventStreamBridge(core, fastConfigDoor()).stream(7L, 0L, writer);

        assertEquals(List.of("step", "thinking", "step"), writer.frameTypes);
        assertEquals(List.of(1L, 2L, 3L), writer.seqs);
        assertEquals("桶1 完成", writer.payloads.get(1));
        // ProcessEventPump meta shape: rows with a landing time ride the
        // {tpTS, d} envelope (the browser unwraps it back into detail).
        assertEquals("{\"tpTS\":\"2026-11-09T10:00:00\",\"d\":\"{\\\"promptTokens\\\":10}\"}",
                writer.metas.get(1));
        assertTrue(writer.completed, "a settled document must seal the stream");
    }

    @Test
    void sealsImmediatelyWhenNothingIsExtracting() {
        CoreDocumentClient core = mock(CoreDocumentClient.class);
        when(core.extractionEvents(eq(7L), eq(0L), any())).thenReturn(List.of());
        when(core.extraction(anyLong(), eq(1), eq(1))).thenReturn(preview(4));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        long started = System.currentTimeMillis();
        new ExtractionEventStreamBridge(core, fastConfigDoor()).stream(7L, 0L, writer);

        assertTrue(writer.completed);
        assertTrue(writer.frameTypes.isEmpty());
        assertTrue(System.currentTimeMillis() - started < 1_000,
                "a settled document must not park a virtual thread");
    }

    @Test
    void emitsHeartbeatWhileTheRunStaysQuiet() throws InterruptedException {
        CoreDocumentClient core = mock(CoreDocumentClient.class);
        when(core.extractionEvents(anyLong(), anyLong(), any())).thenReturn(List.of());
        when(core.extraction(anyLong(), eq(1), eq(1))).thenReturn(preview(3));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        long deadline = System.currentTimeMillis() + 1_500;
        ExtractionEventStreamBridge bridge =
                new ExtractionEventStreamBridge(core, fastConfigDoor());
        Thread pump = Thread.ofVirtual()
                .start(() -> bridge.stream(7L, 0L, writer));
        while (writer.heartbeats == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(writer.heartbeats >= 1, "silence must look alive through heartbeat frames");
        assertTrue(!writer.completed, "a still-extracting document must not seal the stream");
        // The idle-timeout below (stubbed far) would eventually end it; for the
        // spectrum the pump keeps running until the door is cut — latch abort.
        when(core.extraction(anyLong(), eq(1), eq(1))).thenReturn(preview(2));
        pump.join(3_000);
    }

    @Test
    void endsWithOneHonestErrorFrameWhenTheEventDoorIsDown() {
        CoreDocumentClient core = mock(CoreDocumentClient.class);
        when(core.extractionEvents(anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("core 不可达"));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new ExtractionEventStreamBridge(core, fastConfigDoor()).stream(7L, 0L, writer);

        assertEquals(List.of("error"), writer.frameTypes);
        assertTrue(writer.payloads.get(0).contains("core 不可达"));
        assertTrue(writer.completed);
    }

    @Test
    void everyPullAsksCoreFromTheLastCursorTheViewerSaw() {
        CoreDocumentClient core = mock(CoreDocumentClient.class);
        List<Long> requestedCursors = new ArrayList<>();
        when(core.extractionEvents(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            long cursor = inv.getArgument(1);
            requestedCursors.add(cursor);
            return cursor == 0L
                    ? List.of(row(1, "step", "提取启动", "{}"))
                    : List.of();
        });
        when(core.extraction(anyLong(), eq(1), eq(1))).thenReturn(preview(3), preview(4));

        StreamFrameRecorder writer = new StreamFrameRecorder();
        new ExtractionEventStreamBridge(core, fastConfigDoor()).stream(7L, 0L, writer);

        assertEquals(1L, writer.seqs.get(0));
        verify(core, atLeastOnce()).extractionEvents(eq(7L), eq(1L), any());
        assertEquals(1, requestedCursors.stream().filter(c -> c == 0L).count(),
                "cursor 0 must be asked exactly once — never rewound after row 1 landed");
        assertTrue(requestedCursors.stream().allMatch(c -> c >= 0L));
        // Strictly non-decreasing and never rewinding past delivered rows.
        for (int i = 1; i < requestedCursors.size(); i++) {
            assertTrue(requestedCursors.get(i) >= requestedCursors.get(i - 1),
                    "cursor must never rewind: " + requestedCursors);
        }
        assertTrue(requestedCursors.contains(1L),
                "after writing row 1 the pump must pull from cursor 1, never from 0 again");
    }
}
