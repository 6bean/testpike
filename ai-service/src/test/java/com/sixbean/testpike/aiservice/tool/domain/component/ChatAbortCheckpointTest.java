package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chat-abort checkpoints : the user's
 * abort command seals the ledger in core, but until this package the
 * execution plane never observed it — remaining tool rounds, goal-ledger
 * auto batches and the reconcile ring kept launching provider calls and
 * burning tokens against an already-cancelled turn. The contract pinned
 * here: once the abort is visible at a checkpoint, NEW provider launches
 * are zero, the loop exits at the boundary (in-flight tails drain legally,
 * same posture as the budget-gate family), and the ledger sink trips the
 * shared cooperative-cancel signal exactly once (fail-soft door, no
 * re-poll after the trip, unwired sinks byte-identical legacy).
 */
class ChatAbortCheckpointTest {

    private final ReActStreamHarness fx = new ReActStreamHarness();
    private final LlmFallbackChain chain = fx.chain;

    private void mountOneTool() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(java.util.List.of(fx.toolItem(11, "kb_http", 1, false)));
    }

    @Test
    void abortVisibleBeforeStartLaunchesZeroProviderCalls() {
        mountOneTool();
        ReActSteeringSink sink = new ReActSteeringSink();
        sink.abortFromPoll = 1;

        var outcome = fx.runner().runStreaming(fx.baseRequest("提问"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        verify(chain, never()).chatStreamHandle(any(), anyLong(), any(), any());
        verify(chain, never()).chatHandle(any(), anyLong(), any());
        assertNull(outcome.result(), "中止出口不得伪造终答");
    }

    @Test
    void abortAtRoundBoundaryStopsTheNextRound() {
        mountOneTool();
        // Round 1 answers plain text; the reconcile door nudges once so the
        // loop reaches the NEXT round head — poll #3 there sees the abort,
        // the second provider call must never launch.
        fx.script(fx.deltas("半程"), fx.finalAnswer("部分完成"));
        ReActSteeringSink sink = new ReActSteeringSink();
        sink.reconciler = transcript -> "【目标账本】仍有未完成步骤，请继续";
        sink.abortFromPoll = 3;

        var outcome = fx.runner().runStreaming(fx.baseRequest("把目标做完"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        verify(chain, times(1)).chatStreamHandle(any(), anyLong(), any(), any());
        assertNull(outcome.result());
        assertFalse(outcome.limitReached());
    }

    @Test
    void ledgerSinkTripsOnceAndStopsRePollingTheDoor() {
        CoreGateway gateway = mock(CoreGateway.class);
        AtomicBoolean sealed = new AtomicBoolean(false);
        AtomicInteger polls = new AtomicInteger();
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L, null, null,
                () -> {
                    polls.incrementAndGet();
                    return sealed.get();
                });

        assertFalse(sink.aborted());
        assertFalse(sink.aborted());
        sealed.set(true);
        assertTrue(sink.aborted());
        assertTrue(sink.aborted());
        assertTrue(sink.aborted());
        org.junit.jupiter.api.Assertions.assertEquals(3, polls.get(),
                "the door must not be re-polled once the signal tripped (volatile read suffices)");
    }

    @Test
    void doorFaultFailsSoftThenTripsLater() {
        CoreGateway gateway = mock(CoreGateway.class);
        AtomicBoolean faulty = new AtomicBoolean(true);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L, null, null,
                () -> {
                    if (faulty.getAndSet(false)) {
                        throw new IllegalStateException("core read door down");
                    }
                    return true;
                });

        assertFalse(sink.aborted(), "门不可达不得误杀在跑的回合");
        assertTrue(sink.aborted());
    }

    @Test
    void unwiredSinkKeepsLegacyNeverAborted() {
        CoreGateway gateway = mock(CoreGateway.class);
        assertFalse(new FrameLedgerStreamSink(gateway, 9L, 42L).aborted());
    }
}
