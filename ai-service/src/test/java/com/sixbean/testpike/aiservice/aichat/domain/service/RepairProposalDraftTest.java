package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.ScriptView;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.repair.domain.component.RepairPromptTemplate;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repair-proposal draft frame on the dialog chain :
 * when a badge-deep-link turn carries a RESOLVED failure diagnosis, ai-service
 * asks the repair proposal kernel (B4 prompt template, single source) and,
 * only if the proposal passes the action-JSON validator, journals ONE draft
 * frame before the done frame — a proposal card, never an applied change.
 * <p>The no-confirmation zero-landing assertion is the red line of this segment:
 * no spectrum here may touch the script-save or trial-task doors. Landing is
 * the confirmation chain's job (knife 3b) and lands only behind a confirm
 * token, exactly like the tool write gate.
 * <p>L2 posture identical to ChatRunOrchestratorTest: gateway/chain/broker legs
 * isolated, the generator and draft assembly run for real (the proposal
 * parsing/validation itself is never mocked).
 */
class RepairProposalDraftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OLD_ACTION_JSON = """
            {"version":"v2","caseId":"TC-RPR-001","endType":"web",
             "setup":[{"action":"goto","target":{"url":"http://localhost:8899/login"}}],
             "steps":[{"action":"click","target":{"css":"#login-btn","chain":["css"]}},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"rtl-test"}}
            """;
    private static final String NEW_ACTION_JSON = """
            {"version":"v2","caseId":"TC-RPR-001","endType":"web",
             "setup":[{"action":"goto","target":{"url":"http://localhost:8899/login"}}],
             "steps":[{"action":"click","target":{"css":"#submit-order","chain":["css"]}},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"rtl-test"}}
            """;

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);
    private final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    private final AiCallLogWriter callLog = mock(AiCallLogWriter.class);

    private ChatRunOrchestrator harness(String draftSwitchValue, String proposalContent) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn("true");
        when(gateway.getSysConfig("ai.chat.repair-draft.enabled")).thenReturn(draftSwitchValue);
        when(gateway.getSysConfig("ai.repair.model")).thenReturn("glm-repair-test");
        when(gateway.getScript(11L)).thenReturn(
                new ScriptView(11L, "v2", OLD_ACTION_JSON, 7));
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(GroundingOutcome.none());
        when(chain.chatHandle(any(), eq(3L), isNull())).thenReturn(handle(proposalContent));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));
        RepairProposalGenerator generator = new RepairProposalGenerator(chain, router,
                gateway, new RepairPromptTemplate(), new ActionJsonValidator(), callLog, MAPPER);
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, generator, null, null,
                new com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver(
                        gateway, new com.sixbean.testpike.aiservice.aichat.domain.component
                                .AiCtxProperties()),
                null);
    }

    /** Badge turn with a resolved diagnosis over execution 55 / case 11. */
    private static AiChatRunMessage diagnosedRun() {
        return new AiChatRunMessage(9L, 42L, 3L, 7L, "这次执行为什么失败，帮我修", List.of(), 55L, List.of());
    }

    private void stubResolvedDiagnosis() {
        when(gateway.getRepairTaskView(55L)).thenReturn(new RepairTaskView(
                3L, 11L, 4, "v2", OLD_ACTION_JSON, 2, "元素未找到：#submit-order"));
    }

    private static LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "glm-repair-test",
                200, 60, 900L),
                new LlmEndpointConfig("test-provider", "glm-repair-test", "k", "http://x", "config"));
    }

    private static ReActOutcome outcome(int prompt, int completion) {
        return new ReActOutcome(new LlmChatResult("答", "p", "m", prompt, completion, 5L, null),
                List.of(), 0, 0, 0, false, null);
    }

    private String captureDraftPayload(ChatRunOrchestrator orchestrator,
                                       ArgumentCaptor<String> meta) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("draft"),
                payload.capture(), meta.capture());
        return payload.getValue();
    }

    // ---- spectrum 1: resolved diagnosis + valid proposal = exactly one draft
    // frame with the full machine meta, BEFORE the done frame, zero landing ----

    @Test
    void resolvedDiagnosisWithValidProposalJournalsOneDraftFrameAndNeverLands() throws Exception {
        stubResolvedDiagnosis();
        ChatRunOrchestrator orchestrator = harness("true", NEW_ACTION_JSON);

        orchestrator.run(diagnosedRun());

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        String payload = captureDraftPayload(orchestrator, meta);
        assertTrue(payload.contains("确认"), payload);
        JsonNode card = MAPPER.readTree(meta.getValue());
        assertEquals(55L, card.get("executionId").asLong());
        assertEquals(11L, card.get("caseId").asLong());
        assertEquals(7, card.get("baseVersion").asInt());
        assertEquals("v2", card.get("schemaVersion").asText());
        assertEquals("glm-repair-test", card.get("model").asText());
        // the proposal body itself rides the card so the confirm chain (3b)
        // can persist a trustworthy snapshot; it round-trips as real JSON
        JsonNode repaired = MAPPER.readTree(card.get("actionJson").asText());
        assertTrue(repaired.toString().contains("#submit-order"), repaired.toString());
        // draft frame precedes the done frame (a sealed turn gains no frames)
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(gateway);
        inOrder.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("draft"), any(), any());
        inOrder.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("done"), any(), isNull());
        // proposal cost rides the same ledger as every other LLM call
        verify(callLog).insert(eq(3L), eq("test-provider"), eq("glm-repair-test"),
                eq("对话修复提案"), anyInt(), anyInt(), anyLong());
        // RED LINE: proposal only — no save, no trial, no confirmation bypass
        verify(gateway, never()).saveRepairedScript(anyLong(), any(), any());
        verify(gateway, never()).createRepairTrialTask(anyLong(), any(), any());
    }

    // ---- spectrum 2: unparseable / invalid proposal = honest absence, the
    // answer stream is untouched and still closes normally ----

    @Test
    void invalidProposalEmitsNoDraftFrameAndTheTurnStillCompletes() {
        stubResolvedDiagnosis();
        ChatRunOrchestrator orchestrator = harness("true", "抱歉，我无法给出修复。");

        orchestrator.run(diagnosedRun());

        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("draft"),
                any(), any());
        verify(gateway).closeChatMessage(42L, "done");
        verify(gateway, never()).saveRepairedScript(anyLong(), any(), any());
    }

    // ---- spectrum 3: no diagnosis on the turn = no proposal call at all ----

    @Test
    void plainQuestionWithoutDiagnosisNeverCallsTheRepairKernel() {
        ChatRunOrchestrator orchestrator = harness("true", NEW_ACTION_JSON);
        AiChatRunMessage plain = new AiChatRunMessage(9L, 42L, 3L, 7L,
                "今天天气如何", List.of(), null, List.of());

        orchestrator.run(plain);

        verify(chain, never()).chatHandle(any(), anyLong(), isNull());
        verify(gateway, never()).getScript(anyLong());
        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("draft"),
                any(), any());
    }

    // ---- spectrum 4: switch off = zero outbound call, one state frame that
    // names where to turn it on and the key itself (R32 guidance contract) ----

    @Test
    void switchOffSkipsTheKernelAndPointsAtTheSettingByItsRealName() {
        stubResolvedDiagnosis();
        ChatRunOrchestrator orchestrator = harness("false", NEW_ACTION_JSON);

        orchestrator.run(diagnosedRun());

        verify(chain, never()).chatHandle(any(), anyLong(), isNull());
        // the grounding line is also a state frame, so filter by payload
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.atLeast(2)).appendChatFrame(anyLong(), anyLong(),
                type.capture(), text.capture(), isNull());
        String guidance = "";
        for (int i = 0; i < type.getAllValues().size(); i++) {
            if ("state".equals(type.getAllValues().get(i))
                    && text.getAllValues().get(i).contains("ai.chat.repair-draft")) {
                guidance = text.getAllValues().get(i);
            }
        }
        assertTrue(guidance.contains("系统管理"), guidance);
        assertTrue(guidance.contains("系统配置"), guidance);
        assertTrue(guidance.contains("ai.chat.repair-draft.enabled"), guidance);
        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("draft"),
                any(), any());
    }
}
