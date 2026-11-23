package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ClarifyTool;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog wiring of the clarification card : a valid
 * chat.clarify call in the finished transcript journals exactly ONE 'clarify'
 * frame before the done frame (question + 2-4 options + missing slot in its
 * meta, so a reload replays the question that was actually asked); the hot
 * budget at or below zero degrades the same content to a plain-text delta
 * frame instead of silently dropping the question; and a transcript that
 * routed a write call journals no clarify frame at all (a card that both
 * asks and proposes would break the one-action-per-card contract).
 * <p>L2 posture: the gateway door is the mocked remote (same isolation as
 * ChatRunOrchestratorGoalPlanWiringTest) — the proof here is the journaling
 * ladder itself, not the transport.
 */
class ChatRunOrchestratorClarifyFrameTest {

    private static final AiChatRunMessage RUN =
            new AiChatRunMessage(9L, 42L, 3L, 7L, "给项目生成一批用例", List.of(), null, List.of());

    private static final String CLARIFY_BODY = """
            {"question":"要给哪个项目生成用例？",\
            "options":["回归演示项目：给现有需求点补一批用例",\
            "testPike 体验样例：按新上传的需求文档新建用例"],\
            "missingSlot":"目标项目"}""";

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

    private static ReActOutcome clarifyOutcome() {
        return new ReActOutcome(new LlmChatResult("答", "p", "m", 10, 5, 5L, null),
                List.of(
                        ChatMessage.assistantWithToolCalls(null, List.of(
                                new LlmToolCall("c-clarify", ClarifyTool.NAME, CLARIFY_BODY))),
                        ChatMessage.tool("c-clarify", "追问卡已记录（共 2 个选项），本轮到此结束")),
                1, 1, 0, false, null);
    }

    /** All frame types journaled through the gateway door, in call order. */
    private List<String> capturedTypes() {
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).appendChatFrame(
                eq(9L), eq(42L), type.capture(), any(), any());
        return type.getAllValues();
    }

    private List<String> payloadsOfType(String frameType) {
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).appendChatFrame(
                eq(9L), eq(42L), type.capture(), payload.capture(), meta.capture());
        List<String> hits = new java.util.ArrayList<>();
        for (int i = 0; i < type.getAllValues().size(); i++) {
            if (frameType.equals(type.getAllValues().get(i))) {
                hits.add(payload.getAllValues().get(i) + "|" + meta.getAllValues().get(i));
            }
        }
        return hits;
    }

    @Test
    void acceptedClarifyCallJournalsOneClarifyCardBeforeTheDoneFrame() {
        harness(clarifyOutcome()).run(RUN);

        List<String> types = capturedTypes();
        assertEquals(1, types.stream().filter("clarify"::equals).count(),
                "one accepted clarify call must journal exactly one card frame: " + types);
        assertTrue(types.contains("done"), "the turn must still seal with its done frame: " + types);
        assertTrue(types.indexOf("clarify") < types.indexOf("done"),
                "the clarify card rides the same ladder before done: " + types);

        List<String> cards = payloadsOfType("clarify");
        String card = cards.get(0);
        assertTrue(card.contains("要给哪个项目生成用例？"), "the question text is on the card: " + card);
        assertTrue(card.contains("回归演示项目") && card.contains("体验样例"),
                "every option sentence is on the card: " + card);
        assertTrue(card.contains("\"question\"") && card.contains("\"options\"")
                        && card.contains("\"missingSlot\""),
                "the meta carries the render shape: " + card);
    }

    @Test
    void exhaustedBudgetDegradesTheQuestionToPlainText() {
        when(gateway.getSysConfig(ClarifyTool.KEY_PER_TURN_MAX)).thenReturn("0");

        harness(clarifyOutcome()).run(RUN);

        List<String> types = capturedTypes();
        assertEquals(0, types.stream().filter("clarify"::equals).count(),
                "budget 0 = no card frame: " + types);
        List<String> plain = payloadsOfType("delta");
        assertTrue(plain.stream().anyMatch(p -> p.contains("要给哪个项目生成用例？")
                        && p.contains("回归演示项目")),
                "the question still reaches the user as plain text: " + plain);
    }

    @Test
    void writeRoutedTranscriptJournalsNoClarifyFrame() {
        when(gateway.getDraftToolKinds(3L, 7L)).thenReturn(
                List.of(new DraftToolKind("case.create", "case")));
        ReActOutcome mixed = new ReActOutcome(
                new LlmChatResult("答", "p", "m", 10, 5, 5L, null),
                List.of(ChatMessage.assistantWithToolCalls(null, List.of(
                        new LlmToolCall("c-write", "case.create", "{}"),
                        new LlmToolCall("c-clarify", ClarifyTool.NAME, CLARIFY_BODY)))),
                1, 1, 0, false, null);

        harness(mixed).run(RUN);

        List<String> types = capturedTypes();
        assertEquals(0, types.stream().filter("clarify"::equals).count(),
                "a turn that routed a write call never shows the clarify card: " + types);
        assertTrue(payloadsOfType("delta").stream().noneMatch(p ->
                        p.contains("要给哪个项目生成用例？")),
                "and never smuggles the question in as degraded text either");
    }
}
