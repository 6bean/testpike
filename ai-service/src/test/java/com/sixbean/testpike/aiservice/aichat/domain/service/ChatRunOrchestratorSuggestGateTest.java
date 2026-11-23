package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.aiservice.tool.domain.component.SuggestTool;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery gate of the proactive-extension chips :
 * accepted chat.suggest calls ride the DONE frame as a suggest[] array —
 * never a new frame type — and every proposal is re-checked server-side
 * against the turn's mounted intersection BEFORE journaling. Out-of-scope
 * capabilities drop their whole entry; a gate-door fault drops the chips but
 * never takes the seal down; no suggestions keeps the done payload
 * byte-identical to the legacy shape.
 * <p>L2 posture: the gateway door and the runner are the mocked remote (same
 * isolation as ChatRunOrchestratorClarifyFrameTest) — the proof here is the
 * gating ladder itself, not the transport.
 */
class ChatRunOrchestratorSuggestGateTest {

    private static final AiChatRunMessage RUN =
            new AiChatRunMessage(9L, 42L, 3L, 7L, "给项目生成一批用例", List.of(), null, List.of());

    private static final String SUGGEST_BODY = """
            {"suggestions":[{"text":"把缺覆盖的需求点补一批用例","capability":"case.write"},\
            {"text":"删除整个演示项目","capability":"project.delete"}]}""";

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);

    private ChatRunOrchestrator harness(ReActOutcome outcome) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn("true");
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(GroundingOutcome.none());
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome);
        AiCtxConfigResolver ctx = new AiCtxConfigResolver(gateway, new AiCtxProperties());
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, null, null, null, ctx, null);
    }

    private static ReActOutcome suggestOutcome() {
        return new ReActOutcome(new LlmChatResult("答", "p", "m", 10, 5, 5L, null),
                List.of(
                        ChatMessage.assistantWithToolCalls(null, List.of(
                                new LlmToolCall("c-suggest", SuggestTool.NAME, SUGGEST_BODY))),
                        ChatMessage.tool("c-suggest", "扩展建议已记录 2 条，请照常给出最终回答。"),
                        new ChatMessage("assistant", "最终回答")),
                1, 1, 0, false, null);
    }

    /** Payload of the single done frame journaled through the gateway door. */
    private String donePayload() {
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).appendChatFrame(
                eq(9L), eq(42L), type.capture(), payload.capture(), any());
        List<String> hits = new java.util.ArrayList<>();
        for (int i = 0; i < type.getAllValues().size(); i++) {
            if ("done".equals(type.getAllValues().get(i))) {
                hits.add(payload.getAllValues().get(i));
            }
        }
        assertTrue(hits.size() == 1, "exactly one done frame: " + hits);
        return hits.get(0);
    }

    @Test
    void intersectionKeepsInScopeEntriesAndDropsOutliers() {
        when(runner.allowedCapabilities(3L, 7L))
                .thenReturn(Set.of("case.write", "aggregate.query"));

        harness(suggestOutcome()).run(RUN);

        String done = donePayload();
        assertTrue(done.contains("\"suggest\":["), "the chips ride the done frame: " + done);
        assertTrue(done.contains("把缺覆盖的需求点补一批用例"), "in-scope text is delivered: " + done);
        assertTrue(done.contains("\"case.write\""), "in-scope capability is named: " + done);
        assertFalse(done.contains("project.delete"), "out-of-scope entry dropped: " + done);
        assertFalse(done.contains("删除整个演示项目"), "its sentence dropped with it: " + done);
        // The seal still lands right after the journaling, frame ladder intact.
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void noRoutableSuggestionKeepsTheLegacyPayloadByteIdentical() {
        when(runner.allowedCapabilities(3L, 7L)).thenReturn(Set.of());

        harness(suggestOutcome()).run(RUN);

        String done = donePayload();
        assertFalse(done.contains("suggest"), "empty intersection appends nothing: " + done);
        assertTrue(done.equals("{\"messageId\":42,\"promptTokens\":10,\"completionTokens\":5}"),
                "legacy payload shape untouched: " + done);
    }

    @Test
    void gateDoorFaultDropsChipsWithoutBreakingTheSeal() {
        when(runner.allowedCapabilities(3L, 7L))
                .thenThrow(new RuntimeException("core 装配门不可达"));

        harness(suggestOutcome()).run(RUN);

        String done = donePayload();
        assertFalse(done.contains("suggest"), "a faulted gate never smuggles chips: " + done);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void transcriptWithoutAcceptedSuggestionNeverAsksTheGate() {
        ReActOutcome plain = new ReActOutcome(
                new LlmChatResult("答", "p", "m", 10, 5, 5L, null),
                List.of(new ChatMessage("assistant", "最终回答")),
                0, 0, 0, false, null);

        harness(plain).run(RUN);

        String done = donePayload();
        assertFalse(done.contains("suggest"), "no proposal = no chips: " + done);
        verify(runner, org.mockito.Mockito.never())
                .allowedCapabilities(anyLong(), any());
    }
}
