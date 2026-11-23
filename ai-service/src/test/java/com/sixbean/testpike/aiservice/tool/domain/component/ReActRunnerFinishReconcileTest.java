package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-text finish reconciliation : when
 * the model answers with plain text while the dialog surface exposes a
 * reconcile door, the door is consulted; a returned reminder joins the
 * conversation as a system message and the loop runs one more round; a spent
 * budget (second finish attempt → null) lets the answer through — no forced
 * loop. With no door the trajectory is byte-identical legacy; a door that
 * throws degrades to the same legacy finish instead of killing the turn.
 */
class ReActRunnerFinishReconcileTest {

    private static final String REMINDER = "【目标账本】仍有未完成步骤，请继续";

    private final ReActStreamHarness fx = new ReActStreamHarness();
    private final LlmFallbackChain chain = fx.chain;

    private void mountOneTool() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
    }

    @Test
    void openLedgerGetsOneSystemReminderThenFinishPasses() {
        mountOneTool();
        fx.script(fx.deltas("半程"), fx.finalAnswer("已完成一半"));
        fx.script(fx.deltas("收尾"), fx.finalAnswer("全部完成"));

        // Budget-one door: first consult returns the reminder, every later
        int[] used = {0};
        ReActFinishReconciler door = transcript -> {
            assertFalse(transcript.isEmpty(), "门必须能看到已完成的对话");
            return used[0]++ == 0 ? REMINDER : null;
        };
        ReActSteeringSink sink = new ReActSteeringSink();
        sink.reconciler = door;

        var outcome = fx.runner().runStreaming(fx.baseRequest("把目标做完"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertFalse(outcome.limitReached());
        assertEquals("全部完成", outcome.result().content());
        ArgumentCaptor<ChatCompletionRequest> cap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, times(2)).chatStreamHandle(cap.capture(), anyLong(), any(), any());
        List<ChatMessage> second = cap.getAllValues().get(1).messages();
        assertTrue(second.stream().anyMatch(m -> "system".equals(m.role())
                        && REMINDER.equals(m.content())),
                "提醒必须以 system 消息进入第二轮请求");
    }

    @Test
    void closedLedgerFinishKeepsLegacyTrajectory() {
        mountOneTool();
        fx.script(fx.deltas("答复"), fx.finalAnswer("这就是全部"));

        ReActSteeringSink sink = new ReActSteeringSink();
        sink.reconciler = transcript -> null;

        var outcome = fx.runner().runStreaming(fx.baseRequest("普通提问"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals("这就是全部", outcome.result().content());
        verify(chain, times(1)).chatStreamHandle(any(), anyLong(), any(), any());
    }

    @Test
    void throwingDoorDegradesToPlainFinishInsteadOfBreakingTurn() {
        mountOneTool();
        fx.script(fx.deltas("答复"), fx.finalAnswer("照常收口"));

        ReActSteeringSink sink = new ReActSteeringSink();
        sink.reconciler = transcript -> {
            throw new IllegalStateException("ledger door exploded");
        };

        var outcome = fx.runner().runStreaming(fx.baseRequest("把目标做完"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals("照常收口", outcome.result().content());
        assertFalse(outcome.limitReached());
        verify(chain, times(1)).chatStreamHandle(any(), anyLong(), any(), any());
    }
}
