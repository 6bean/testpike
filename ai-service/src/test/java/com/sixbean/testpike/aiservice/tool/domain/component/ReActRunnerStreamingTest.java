package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Streaming variant of the ReAct loop : the same
 * assemble→call→backfill→continue orchestration, but every observable moment is
 * pushed to a {@link ReActStreamSink} as it happens — provider deltas map onto
 * the dialog frame kinds (content→delta, reason→reason) and tool dispatch is
 * bracketed by tool_start/tool_done. The loop body itself is NOT rewritten:
 * these specs prove the streaming run shares the non-streaming contract
 * (round accounting, tool backfill pairs, limitReached semantics).
 * <p>Fixture wiring lives in {@link ReActStreamHarness} (single source shared
 * with the steering and dialog-surface specs): the provider chain is mocked,
 * FcAssembly/Dispatcher/RoundPolicy are real.
 */
class ReActRunnerStreamingTest {

    private final ReActStreamHarness fx = new ReActStreamHarness();

    @Test
    void streamingRunEmitsOrderedDeltasAndKeepsOutcomeContract() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId)).thenReturn(List.of());
        fx.script(List.of(
                new LlmStreamEvent(LlmStreamEvent.KIND_REASON, "先看失败清单"),
                new LlmStreamEvent(LlmStreamEvent.KIND_CONTENT, "分析完成")),
                fx.finalAnswer("分析完成"));

        ReActRecordingSink sink = new ReActRecordingSink();
        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals(List.of("reason:先看失败清单", "delta:分析完成"), sink.log,
                "reason pieces precede content pieces exactly as the provider sent them");
        assertEquals("分析完成", outcome.result().content());
        assertFalse(outcome.limitReached());
        assertEquals(0, outcome.rounds(), "empty plan = single plain streaming call, zero tool rounds");
    }

    @Test
    void toolRoundIsBracketedByStartAndDoneThenFinalAnswerStreams() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
        when(fx.gateway.invokeTool(eq(fx.actorId), eq(fx.taskId), eq(11L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"data\":1}", null, null, null, 120L));
        fx.script(fx.deltas(), fx.withTools(List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));
        fx.script(fx.deltas("已", "定位"), fx.finalAnswer("已定位"));

        ReActRecordingSink sink = new ReActRecordingSink();
        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals(List.of("tool_start:kb_http", "tool_done:kb_http:true:{\"data\":1}",
                "delta:已", "delta:定位"), sink.log,
                "trajectory frames surround the dispatch, then the answer streams");
        assertEquals(1, outcome.rounds());
        assertEquals(1, outcome.dispatched());
        List<ChatMessage> msgs = outcome.messages();
        assertEquals(4, msgs.size(), "system+user+assistant+tool — same backfill contract as non-streaming");
        assertEquals("tool", msgs.get(3).role());
    }

    @Test
    void planProposeCallIsJournaledNotDispatchedAndTheToolListCarriesTheBuiltIn() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(13, "kb_read", 1, false)));
        fx.script(fx.deltas(), fx.withTools(List.of(new LlmToolCall("p1",
                com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool.NAME,
                "{\"steps\":[\"甲\",\"乙\",\"丙\"]}"))));
        fx.script(fx.deltas("计划就绪"), fx.finalAnswer("计划就绪"));

        ReActRecordingSink sink = new ReActRecordingSink();
        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("排查这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        org.mockito.Mockito.verify(fx.gateway, org.mockito.Mockito.never())
                .invokeTool(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        List<ChatMessage> msgs = outcome.messages();
        ChatMessage toolMsg = msgs.stream().filter(m -> "tool".equals(m.role())).findFirst()
                .orElseThrow(() -> new AssertionError("plan refusal never backfilled: " + msgs));
        assertTrue(toolMsg.content().contains("确认"),
                "the model is told the plan waits for the user's confirmation: " + toolMsg.content());
        org.mockito.ArgumentCaptor<com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest>
                captor = org.mockito.ArgumentCaptor.forClass(
                com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest.class);
        org.mockito.Mockito.verify(fx.chain, org.mockito.Mockito.atLeast(2))
                .chatStreamHandle(captor.capture(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        boolean mounted = captor.getAllValues().get(captor.getAllValues().size() - 1).tools()
                .stream().anyMatch(d -> d.function().name()
                        .equals(
                                com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool.NAME));
        assertTrue(mounted, "the dialog surface must offer plan.propose on every round: "
                + captor.getAllValues().get(captor.getAllValues().size() - 1).tools());
        assertTrue(sink.log.stream().anyMatch(l -> l.startsWith("tool_done:plan.propose:true")),
                sink.log.toString());
    }

    @Test
    void rejectedToolStillReportsDoneWithSuccessFalse() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(12, "x-write", 1, true)));
        fx.script(fx.deltas(), fx.withTools(List.of(new LlmToolCall("c9", "x-write", "{}"))));
        fx.script(fx.deltas("结束"), fx.finalAnswer("结束"));

        ReActRecordingSink sink = new ReActRecordingSink();
        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertTrue(sink.log.stream().anyMatch(l -> l.startsWith("tool_done:x-write:false")),
                "a refusal is still an observed trajectory event: " + sink.log);
        assertEquals(1, outcome.rejected());
    }

    @Test
    void flowGuideAnswerReachesTheSinkVerbatimForTheLedger() {
        // stage 3a: the browser renders action buttons from the journaled
        // trace meta, so the tool's structured body must arrive unmodified.
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(13, "case.flow-guide", 1, false)));
        when(fx.gateway.invokeTool(eq(fx.actorId), eq(fx.taskId), eq(13L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(13L, "case.flow-guide", true,
                        "{\"caseId\":5,\"states\":[]}", null, null, null, 80L));
        fx.script(fx.deltas(), fx.withTools(List.of(
                new LlmToolCall("c3", "case.flow-guide", "{\"caseId\":5}"))));
        fx.script(fx.deltas("可以评审通过了"), fx.finalAnswer("可以评审通过了"));

        ReActRecordingSink sink = new ReActRecordingSink();
        fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertTrue(sink.log.contains("tool_done:case.flow-guide:true:{\"caseId\":5,\"states\":[]}"),
                "the ledger needs the exact body the model saw: " + sink.log);
    }
}
