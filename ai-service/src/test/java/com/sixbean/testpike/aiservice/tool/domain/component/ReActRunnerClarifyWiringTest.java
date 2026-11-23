package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runner wiring of the built-in chat.clarify channel :
 * the definition rides streamed dialog plans like plan.propose (never the
 * plain chain), a VALID clarify call ends the turn right there — the honest
 * suspension must not burn a second provider call — while a refused shape
 * keeps the loop running so the model corrects against the schema or answers
 * under a stated assumption instead of stalling the user.
 */
class ReActRunnerClarifyWiringTest {

    private static final String CLARIFY_BODY = """
            {"question":"要给哪个项目生成用例？",\
            "options":["回归演示项目：给现有需求点补一批用例",\
            "testPike 体验样例：按新上传的需求文档新建用例"],\
            "missingSlot":"目标项目"}""";

    private final ReActStreamHarness fx = new ReActStreamHarness();
    private final LlmFallbackChain chain = fx.chain;

    private void mountOneTool() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
    }

    @Test
    void clarifyDefinitionRidesEveryStreamedDialogPlan() {
        mountOneTool();
        fx.script(fx.deltas("直接回答"), fx.finalAnswer("直接回答"));

        fx.runner().runStreaming(fx.baseRequest("生成用例"), fx.projectId, null,
                fx.actorId, fx.taskId, null, new ReActSteeringSink());

        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain).chatStreamHandle(req.capture(), anyLong(), any(), any());
        assertNotNull(req.getValue().tools(), "对话面必须带工具清单");
        assertTrue(req.getValue().tools().stream()
                        .anyMatch(t -> ClarifyTool.NAME.equals(t.function().name())),
                "chat.clarify 必须随对话面流式计划挂载: " + req.getValue().tools());
    }

    @Test
    void acceptedClarifyEndsTheTurnWithoutASecondModelCall() {
        mountOneTool();
        // Only round one is scripted: were the loop to ask the provider again
        // after a suspension, the harness would fail with "stream script
        // exhausted" — the burn-a-second-call regression cannot hide.
        fx.script(fx.deltas(), fx.withTools(List.of(
                new LlmToolCall("c-clarify", ClarifyTool.NAME, CLARIFY_BODY))));

        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("生成用例"),
                fx.projectId, null, fx.actorId, fx.taskId, null, new ReActSteeringSink());

        verify(chain, times(1)).chatStreamHandle(any(), anyLong(), any(), any());
        assertEquals(1, outcome.rounds());
        assertEquals(1, outcome.dispatched(), "clarify 成功记账为一次派发");
        assertFalse(outcome.limitReached(), "挂起终局不是轮次上限退出");
        // The transcript keeps the tool result pair — the ledger replay and a
        boolean toolResultKept = outcome.messages().stream()
                .anyMatch(m -> "tool".equals(m.role()));
        assertTrue(toolResultKept, "clarify 的 tool 结果必须留在对话轨迹里");
    }

    @Test
    void refusedClarifyShapeKeepsTheLoopRunning() {
        mountOneTool();
        fx.script(fx.deltas(), fx.withTools(List.of(new LlmToolCall("c-bad",
                ClarifyTool.NAME, "{\"question\":\"哪个项目？\",\"options\":[\"只有一条\"]}"))));
        fx.script(fx.deltas("补答"), fx.finalAnswer("按最可能解释直接作答"));

        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("生成用例"),
                fx.projectId, null, fx.actorId, fx.taskId, null, new ReActSteeringSink());

        verify(chain, times(2)).chatStreamHandle(any(), anyLong(), any(), any());
        assertEquals(1, outcome.rejected(), "不合形态的追问记为一次拒绝");
    }

    @Test
    void acceptedClarifyConsultsTheSuspendLaneWithThePairedTranscript() {
        mountOneTool();
        fx.script(fx.deltas(), fx.withTools(List.of(
                new LlmToolCall("c-clarify", ClarifyTool.NAME, CLARIFY_BODY))));

        ReActSteeringSink sink = new ReActSteeringSink();
        ReActRecordingReconciler door = new ReActRecordingReconciler();
        sink.reconciler = door;

        fx.runner().runStreaming(fx.baseRequest("生成用例"), fx.projectId, null,
                fx.actorId, fx.taskId, null, sink);

        assertEquals(1, door.suspendCalls.size(),
                "clarify 挂起必须走挂起账本通道恰一次，reload 才看得到可恢复的目标");
        List<ChatMessage> paired = door.suspendCalls.get(0);
        assertTrue(paired.stream().anyMatch(m -> "tool".equals(m.role())),
                "挂起通道必须收到含 tool 结果对的完整对话");
        assertTrue(paired.stream().anyMatch(m -> "assistant".equals(m.role())
                        && m.toolCalls() != null
                        && m.toolCalls().stream().anyMatch(c ->
                        ClarifyTool.NAME.equals(c.function().name()))),
                "挂起通道必须看到模型发起的 clarify 调用本身");
        assertEquals(0, door.finishCalls, "挂起终局不得绕过式地再走拟收口通道");
    }

    @Test
    void plainTextFinishNeverTouchesTheSuspendLane() {
        mountOneTool();
        fx.script(fx.deltas("答"), fx.finalAnswer("完成"));

        ReActSteeringSink sink = new ReActSteeringSink();
        ReActRecordingReconciler door = new ReActRecordingReconciler();
        sink.reconciler = door;

        fx.runner().runStreaming(fx.baseRequest("普通提问"), fx.projectId, null,
                fx.actorId, fx.taskId, null, sink);

        assertEquals(0, door.suspendCalls.size(),
                "只有挂起终局才走挂起通道，普通收口保持原样");
        assertTrue(door.finishCalls >= 1, "普通收口照常咨询拟收口通道");
    }
}
