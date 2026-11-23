package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingCitation;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog-run orchestration contract on the ai-service side (AO batch
 * step C of γ): one broker message = one streaming ReAct turn whose
 * every moment is journaled through the frame-ledger door, closed with a done
 * frame (usage attached) BEFORE the turn is sealed, or with an honest error
 * frame when the execution switch is off or the provider chain fails.
 * <p>L2 unit posture identical to FrameLedgerStreamSinkTest: the core gateway,
 * the ReAct loop and the project router are isolated here — the real HTTP
 * wiring of the ledger door is pinned by CoreGatewayFrameLedgerTest, the
 * streaming loop contract by ReActRunnerStreamingTest, the ledger semantics by
 * the core-side tests. Nothing faked is re-proved at this layer.
 */
class ChatRunOrchestratorTest {

    private static final AiChatRunMessage RUN =
            new AiChatRunMessage(9L, 42L, 3L, 7L, "帮我看看昨天失败的用例", List.of(), null, List.of());

    /**
 * S4 identity channel : the vertical verdict rides the run
 * message as a DTO field (never a session lookup inside ai-service), and
 * the orchestrator forwards it verbatim into grounding assembly — the one
 * place the trim map is enforced.
 */
    @Test
    void systemManagerVerdictOfTheRunRidesIntoGroundingAssembly() {
        ChatRunOrchestrator orchestrator = harness("true");
        AiChatRunMessage managerRun = new AiChatRunMessage(9L, 42L, 3L, 7L,
                "这个项目谁负责哪些缺陷", List.of(), null, List.of(), true);

        orchestrator.run(managerRun);

        verify(grounding).assemble(3L, managerRun.prompt(), true);

        ChatRunOrchestrator memberHarness = harness("true");
        memberHarness.run(RUN);
        verify(grounding).assemble(3L, RUN.prompt(), false);
    }

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);

    // ---- : behaviour directives ride the published template ----

    private static final String BEHAVIOR_KEY =
            com.sixbean.testpike.aiservice.aichat.domain.component
                    .ChatBehaviorDirectives.KEY_ENABLED;

    /**
     * The goal-echo clause group is a second template read behind its own gate.
     * An unstubbed key reads as absent, and an absent value keeps the echo on,
     * so a test that claims "closed switch never reads a template" must close
     * both gates — naming this key is the only honest way to express that.
     */
    private static final String GOAL_ECHO_KEY =
            com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.KEY_ENABLED;

    private static CoreGateway.PromptTemplateBrief publishedBehaviorRow(String content) {
        return new CoreGateway.PromptTemplateBrief(77L,
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_NAME,
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_DOMAIN, 1, content, 2);
    }

    private String systemHeadOfSingleTurn(ChatRunOrchestrator orchestrator) {
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));
        orchestrator.run(RUN);
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner, times(1)).runStreaming(captor.capture(), eq(3L), any(), eq(7L),
                isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        return captor.getValue().messages().get(0).textContent();
    }

    @Test
    void publishedBehaviorTemplateBecomesTheSystemHeadWithNoExtraModelCall() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        when(gateway.promptTemplateLatestByStatus(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_NAME,
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_DOMAIN, 2))
                .thenReturn(publishedBehaviorRow("已发布的行为条款正文\n"));

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("已发布的行为条款正文"),
                "the published template is the single source of the head: " + head);
        assertFalse(head.contains("你是 testPike 的在线 AI 助手"),
                "the conservative constant must not be appended next to the published text");
        // assembly adds zero extra model calls: exactly one streaming turn ran
        verify(router, times(1)).route(anyLong());
    }

    @Test
    void closedSwitchKeepsTheConservativeHeadAndSkipsTheTemplateRead() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("0");
        when(gateway.getSysConfig(GOAL_ECHO_KEY)).thenReturn("0");
        // The attribution clause group owns its own gate since round-2 item 1;
        // a fully conservative head has to close all three reader families.
        when(gateway.getSysConfig(com.sixbean.testpike.aiservice.aichat.domain.component
                .GoalAttributionGate.KEY_ENABLED)).thenReturn("0");

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("你是 testPike 的在线 AI 助手"),
                "explicitly off = conservative built-in clause: " + head);
        verify(gateway, never()).promptTemplateLatestByStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void missingPublishedRowKeepsTheConservativeHead() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        // no stubbing of promptTemplateLatestByStatus: the family has no row

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("你是 testPike 的在线 AI 助手"),
                "absent row = never an empty head, the chain must not break: " + head);
    }

    // ---- residual-2: every dialog head shape carries the kernel invariant once ----

    private int dialogKernelInvariantCount(String head) {
        return com.sixbean.testpike.aiservice.llm.pipeline
                .BehaviorKernelAssembly.kernelClauseCount(head);
    }

    @Test
    void publishedBehaviorRowHeadCarriesKernelInvariantExactlyOnce() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        when(gateway.promptTemplateLatestByStatus(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_NAME,
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_DOMAIN, 2))
                .thenReturn(publishedBehaviorRow("已发布的行为条款正文\n"));

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("已发布的行为条款正文")
                        && dialogKernelInvariantCount(head) == 1,
                "published row stays the head body while the kernel invariant rides once: " + head);
    }

    @Test
    void conservativeFallbackHeadCarriesKernelInvariantExactlyOnce() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        // family has no published row: the built-in contract is the head body

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("你是 testPike 的在线 AI 助手")
                        && dialogKernelInvariantCount(head) == 1,
                "the fallback head still carries the invariant exactly once: " + head);
    }

    @Test
    void closedSwitchHeadCarriesKernelInvariantOnceAndNeverReadsTheTemplate() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("0");
        when(gateway.getSysConfig(GOAL_ECHO_KEY)).thenReturn("0");
        // Third reader family (goal attribution) must be closed too for the
        // legacy zero-template-read posture to hold (round-2 item 1 wiring).
        when(gateway.getSysConfig(com.sixbean.testpike.aiservice.aichat.domain.component
                .GoalAttributionGate.KEY_ENABLED)).thenReturn("0");

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(dialogKernelInvariantCount(head) == 1,
                "explicitly off keeps the invariant floor: " + head);
        verify(gateway, never()).promptTemplateLatestByStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void failingTemplateReadDegradesToContractHeadWithInvariantOnce() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        when(gateway.promptTemplateLatestByStatus(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_NAME,
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .ChatBehaviorDirectives.TEMPLATE_DOMAIN, 2))
                .thenThrow(new IllegalStateException("core read unavailable"));

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("你是 testPike 的在线 AI 助手")
                        && dialogKernelInvariantCount(head) == 1,
                "read failure degrades to the contract head, never an empty one: " + head);
    }

    @Test
    void behaviorTemplateReadFailureFallsBackWithoutBreakingTheTurn() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        when(gateway.promptTemplateLatestByStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenThrow(new IllegalStateException("core door unavailable"));

        String head = systemHeadOfSingleTurn(orchestrator);

        assertTrue(head.contains("你是 testPike 的在线 AI 助手"),
                "a failing template read is fail-soft on the assembly: " + head);
    }

    @Test
    void eachTurnReReadsThePublishedRowSoHotUpdatesLandNextTurn() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BEHAVIOR_KEY)).thenReturn("1");
        when(gateway.promptTemplateLatestByStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(publishedBehaviorRow("第一版条款\n"));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(1, 1));

        orchestrator.run(RUN);

        // operator publishes a new version on the management page — the very
        // next turn must carry it (no cache anywhere on the read path)
        when(gateway.promptTemplateLatestByStatus(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenReturn(publishedBehaviorRow("第二版条款\n"));
        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner, times(2)).runStreaming(captor.capture(), eq(3L), any(), eq(7L),
                isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        List<ChatCompletionRequest> turns = captor.getAllValues();
        assertTrue(turns.get(0).messages().get(0).textContent().contains("第一版条款"));
        assertTrue(turns.get(1).messages().get(0).textContent().contains("第二版条款"),
                "a template edit lands on the next turn — the read is per-turn, uncached");
    }

    private ChatRunOrchestrator harness(String execEnabled) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn(execEnabled);
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(GroundingOutcome.none());
        // Emitter routing comes from the registry : the harness
        // feeds the same shape the live draft-kinds door returns, so the case
        // spectra below keep proving the SAME behaviour through the new path.
        stubDraftKindsInHarness();
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, null, null, null,
                new com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver(
                        gateway, new com.sixbean.testpike.aiservice.aichat.domain.component
                                .AiCtxProperties()),
                null);
    }

    /**
 * a: the rolling dialog assembly consumes the sealed-history
 * read door. These turns are sealed rows of one session (id only matters
 * for uniqueness here — the assembly is role/content driven).
 */
    private static com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote
            sealedTurn(long id, String role, String content) {
        return new com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote(
                id, 9L, role, content, null, "done", id, id, 1, 2, null, null, null);
    }

    @Test
    void sealedHistoryTurnsRideTheRequestAfterTheInFlightEchoIsDropped() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.listChatMessages(7L, 9L)).thenReturn(List.of(
                sealedTurn(1L, "user", "昨天的失败原因"),
                sealedTurn(2L, "assistant", "以超时为主"),
                sealedTurn(3L, "user", RUN.prompt())));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        // order frozen by AP-1 ②: system head, then sealed turns oldest-first,
        // then the live prompt — the current-turn user echo from the ledger is
        // dropped once so the provider never sees the question twice.
        assertEquals(4, messages.size(), "system + 2 sealed turns + live prompt: " + messages);
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertEquals("昨天的失败原因", messages.get(1).textContent());
        assertEquals("assistant", messages.get(2).role());
        assertEquals(RUN.prompt(), messages.get(3).textContent());
        assertEquals(1, messages.stream().filter(m -> "user".equals(m.role())
                        && RUN.prompt().equals(m.textContent())).count(),
                "the in-flight prompt appears exactly once");
    }

    /**
 * a2: the session's whitelisted pinned context rides the
 * system message's pinned section. Foreign keys inside the payload never
 * reach the prompt (read-side defence over the write door's whitelist).
 */
    @Test
    void pinnedSessionContextRidesTheSystemSectionWithoutUnwhitelistedKeys() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.sessionContext(7L, 9L)).thenReturn(
                "{\"route\":\"/executions\",\"projectId\":3,\"password\":\"s3cret\"}");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        String system = messages.get(0).textContent();
        assertTrue(system.contains("—— 已确认事实 ——"),
                "pinned section header must appear when facts are present: " + system);
        assertTrue(system.contains("本问发起页面 = /executions"), system);
        assertTrue(system.contains("projectId: 3"), system);
        assertTrue(!system.contains("s3cret") && !system.contains("password"),
                "a credential riding the payload must never reach the prompt");
    }

    /**
 * a2 fail-soft contract: a pinned-context door that cannot
 * answer degrades to "no pinned facts" — the turn still runs, history
 * enhancement must never take the dialog down.
 */
    @Test
    void pinnedContextDoorFailureKeepsTheTurnWithoutThePinnedSection() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.sessionContext(7L, 9L)).thenThrow(new RuntimeException("core door down"));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        assertEquals("system", messages.get(0).role());
        assertTrue(!messages.get(0).textContent().contains("—— 已确认事实 ——"),
                "a dead context door must render no pinned section");
        assertEquals(RUN.prompt(), messages.get(messages.size() - 1).textContent());
    }

    @Test
    void historyDoorFailureKeepsTheSingleTurnRequestShape() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.listChatMessages(7L, 9L)).thenThrow(new RuntimeException("core door down"));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        assertEquals(2, messages.size(), "a dead history door must not kill the turn");
        assertEquals("system", messages.get(0).role());
        assertEquals(RUN.prompt(), messages.get(1).textContent());
    }

    @Test
    void keepRecentTurnsSettingTrimsTheRawWindowOfTheHistory() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig("ai.ctx.session-keep-recent-turns")).thenReturn("2");
        when(gateway.listChatMessages(7L, 9L)).thenReturn(List.of(
                sealedTurn(1L, "user", "问一"), sealedTurn(2L, "assistant", "答一"),
                sealedTurn(3L, "user", "问二"), sealedTurn(4L, "assistant", "答二"),
                sealedTurn(5L, "user", "问三"), sealedTurn(6L, "assistant", "答三"),
                sealedTurn(7L, "user", RUN.prompt())));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        // keep=2 (echo already dropped, 3 sealed turns remain): newest two turns
        // raw; the oldest turn is left out of the raw window — this harness has
        // no summariser door, so the trimmed turn renders NO summary section
        // (1b-b contract: a missing door keeps the honest shape).
        assertEquals(6, messages.size(), "system + 问二/答二 + 问三/答三 + live: " + messages);
        assertTrue(messages.stream().noneMatch(m -> "答一".equals(m.textContent())),
                "turns outside the keep window never reach the provider raw");
        assertEquals("问二", messages.get(1).textContent());
        assertEquals(RUN.prompt(), messages.get(5).textContent());
    }

    /**
 * b: with the rolling summariser door wired, the turns that
 * fall out of the raw window ride the system message as ONE summary
 * section — the summariser receives exactly the older segment (recent turns
 * and the live prompt stay raw inputs, pinned facts never enter it).
 */
    @Test
    void olderTurnsRideTheRequestAsOneSummarySectionWhenTheDoorIsWired() {
        java.util.concurrent.atomic.AtomicReference<
                java.util.List<com.sixbean.testpike.aiservice.gen.infrastructure.remote
                        .ChatMessageRemote>> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.sixbean.testpike.aiservice.aichat.domain.component.DialogRollingSummary door =
                mock(com.sixbean.testpike.aiservice.aichat.domain.component
                        .DialogRollingSummary.class);
        when(door.forTurn(eq("m-test"), eq(3L))).thenReturn(older -> {
            seen.set(older);
            return "早前一轮定了超时主因";
        });
        ChatRunOrchestrator orchestrator = harnessWithSummary(door);
        when(gateway.getSysConfig("ai.ctx.session-keep-recent-turns")).thenReturn("1");
        when(gateway.listChatMessages(7L, 9L)).thenReturn(List.of(
                sealedTurn(1L, "user", "问一"), sealedTurn(2L, "assistant", "答一"),
                sealedTurn(3L, "user", "问二"), sealedTurn(4L, "assistant", "答二"),
                sealedTurn(5L, "user", RUN.prompt())));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        var messages = request.getValue().messages();
        String system = messages.get(0).textContent();
        assertTrue(system.contains("—— 更早对话摘要（前 1 轮）——"), system);
        assertTrue(system.contains("早前一轮定了超时主因"), system);
        // the summariser input is exactly the pre-window segment (the two oldest
        // sealed rows): no recent raw rows and no live prompt.
        assertEquals(2, seen.get().size(), String.valueOf(seen.get()));
        assertEquals("问一", seen.get().get(0).content());
        assertEquals("答一", seen.get().get(1).content());
        assertTrue(messages.stream().anyMatch(m -> "答二".equals(m.textContent())),
                "recent turns stay raw alongside the summary");
    }

    private ChatRunOrchestrator harnessWithSummary(
            com.sixbean.testpike.aiservice.aichat.domain.component.DialogRollingSummary door) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn("true");
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(GroundingOutcome.none());
        stubDraftKindsInHarness();
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, null, null, null,
                new com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver(
                        gateway, new com.sixbean.testpike.aiservice.aichat.domain.component
                                .AiCtxProperties()),
                door);
    }

    private void stubDraftKindsInHarness() {
        when(gateway.getDraftToolKinds(anyLong(), anyLong())).thenReturn(List.of(
                kind("case.create", "CASE_WRITE"), kind("case.update", "CASE_WRITE"),
                kind("tag.create", "TAG_WRITE"), kind("tag.rename", "TAG_WRITE"),
                kind("tag.merge", "TAG_WRITE"),
                kind("promptTpl.save", "PROMPT_TPL_WRITE"),
                kind("skill.save", "SKILL_WRITE"),
                kind("case.review", "CASE_REVIEW"),
                kind("plan.create", "PLAN_WRITE"),
                kind("batch.create", "BATCH_WRITE"),
                kind("report.generate", "REPORT_WRITE"),
                kind("apitest.defaults.save", "APITEST_WRITE"),
                kind("apiSpec.pull", "APISPEC_WRITE"),
                kind("perf.run.launch", "PERF_LAUNCH"),
                kind("appauto.reservation.create", "RESERVATION_WRITE"),
                kind("appauto.popup-rule.learn", "POPUP_RULE_LEARN"),
                kind("mock.space.create", "MOCK_SPACE_WRITE"),
                kind("mock.stub.create", "MOCK_STUB_WRITE"),
                kind("mock.stub.update", "MOCK_STUB_WRITE"),
                kind("aiGen.batch.submit", "AIGEN_BATCH_WRITE"),
                kind("defect.create", "DEFECT_WRITE"),
                kind("defect.update", "DEFECT_WRITE"),
                kind("defect.transition", "DEFECT_WRITE"),
                kind("datafactory.create", "DATA_FACTORY_WRITE"),
                kind("datafactory.update", "DATA_FACTORY_WRITE"),
                kind("explore.session.create", "EXPLORE_CREATE")));
    }

    private static ReActOutcome outcome(int prompt, int completion) {
        return new ReActOutcome(new LlmChatResult("答", "p", "m", prompt, completion, 5L, null),
                List.of(), 0, 0, 0, false, null);
    }

    @Test
    void switchOffRefusesHonestlyWithOneErrorFrameAndSealsTheTurn() {
        ChatRunOrchestrator orchestrator = harness("false");

        orchestrator.run(RUN);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), payload.capture(), isNull());
        assertTrue(payload.getValue().contains("在线 AI 对话"), payload.getValue());
        verify(gateway).closeChatMessage(42L, "error");
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void happyPathStreamsThroughTheLedgerSinkThenDoneFrameBeforeClose() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(120, 34));

        orchestrator.run(RUN);

        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ArgumentCaptor<ReActStreamSink> sink = ArgumentCaptor.forClass(ReActStreamSink.class);
        verify(runner).runStreaming(request.capture(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  sink.capture());
        // identity crosses intact: the sink is bound to THIS session + turn
        sink.getValue().onDelta("片段");
        verify(gateway).appendChatFrame(9L, 42L, "delta", "片段", null);
        assertTrue(request.getValue().messages().stream()
                .anyMatch(m -> "user".equals(m.role()) && RUN.prompt().equals(m.textContent())));

        ArgumentCaptor<String> done = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("done"), done.capture(), isNull());
        assertTrue(done.getValue().contains("\"promptTokens\":120"), done.getValue());
        assertTrue(done.getValue().contains("\"completionTokens\":34"), done.getValue());
        // done frame lands BEFORE the seal — a closed turn cannot gain frames
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(gateway);
        inOrder.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("done"), any(), isNull());
        inOrder.verify(gateway).closeChatMessage(42L, "done");
    }

    // Wire-safety contract of the hand-rolled meta JSON (knife 3): excerpts are
    // raw document windows that legitimately contain newlines/tabs/quotes. A
    // literal control character inside the JSON string would make the payload
    // unparsable AND split the SSE block on the wire (meta rides as a comment
    // line) — every scalar must land escaped, meta stays exactly one line.
    @Test
    void citeFrameMetaStaysSingleLineWhenExcerptCarriesControlCharacters() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(grounding.assemble(eq(3L), eq(RUN.prompt()), anyBoolean())).thenReturn(new GroundingOutcome(
                List.of(new GroundingCitation(1, "knowledge", 99L, null, "手册\"引用\"",
                        "第一行\n第二行\t制表", 0.8, null, "77")),
                "[C1] ctx\n", false));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("cite"), any(), meta.capture());
        String value = meta.getValue();
        assertTrue(value.indexOf('\n') < 0 && value.indexOf('\r') < 0,
                "meta must stay one line for the SSE comment channel: " + value);
        assertTrue(value.contains("第一行\\n第二行\\t制表"),
                "control characters must arrive escaped: " + value);
        assertTrue(value.contains("手册\\\"引用\\\""),
                "quotes inside titles must arrive escaped: " + value);
        assertTrue(value.contains("\"chunkId\":\"77\""),
                "knowledge paragraph anchor must ride the meta for the deep link: " + value);
    }

    @Test
    void groundedTurnEmitsStateThenCiteFramesAndFeedsContextBlockToSystemPrompt() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(grounding.assemble(eq(3L), eq(RUN.prompt()), anyBoolean())).thenReturn(new GroundingOutcome(
                List.of(new GroundingCitation(1, "help", 77L, "apitest", "接口测试指南",
                        "集合与请求的编排在左侧列表", 0.9),
                        new GroundingCitation(2, "knowledge", 88L, null, "压测口径说明",
                                "并发档位含义", 0.7)),
                "[C1] 接口测试指南: 集合与请求的编排在左侧列表\n[C2] 压测口径说明: 并发档位含义\n",
                false));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        // two citations → two cite frames, machine-readable meta carries the card fields
        verify(gateway, times(2)).appendChatFrame(eq(9L), eq(42L), eq("cite"), any(),
                meta.capture());
        assertTrue(meta.getAllValues().get(0).contains("\"index\":1"), meta.getAllValues().get(0));
        assertTrue(meta.getAllValues().get(0).contains("\"kind\":\"help\""),
                meta.getAllValues().get(0));
        assertTrue(meta.getAllValues().get(0).contains("\"refId\":77"),
                meta.getAllValues().get(0));
        assertTrue(meta.getAllValues().get(0).contains("\"domainCode\":\"apitest\""),
                meta.getAllValues().get(0));
        assertTrue(meta.getAllValues().get(1).contains("\"kind\":\"knowledge\""),
                meta.getAllValues().get(1));
        assertTrue(meta.getAllValues().get(1).contains("\"domainCode\":null"),
                meta.getAllValues().get(1));
        assertTrue(meta.getAllValues().get(1).contains("\"refId\":88"),
                meta.getAllValues().get(1));
        // cite frames land AFTER the state frame announcing retrieval happened
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(gateway);
        inOrder.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("state"), any(), isNull());
        inOrder.verify(gateway, times(2)).appendChatFrame(eq(9L), eq(42L), eq("cite"), any(),
                any());
        // context block reaches the model inside the system message
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), anyLong(), any(), anyLong(), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        String system = request.getValue().messages().stream()
                .filter(m -> "system".equals(m.role())).findFirst().orElseThrow().textContent();
        assertTrue(system.contains("[C1] 接口测试指南"), system);
        assertTrue(system.contains("仅据"), system);
    }

    @Test
    void zeroHitTurnEmitsNoCiteFramesAndSystemPromptForbidsImprovisedGrounding() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(grounding.assemble(anyLong(), any(), anyBoolean())).thenReturn(GroundingOutcome.none());
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("cite"), any(), any());
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), anyLong(), any(), anyLong(), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        String system = request.getValue().messages().stream()
                .filter(m -> "system".equals(m.role())).findFirst().orElseThrow().textContent();
        // honesty posture of a groundless turn: say you don't know, never fake sources
        assertTrue(system.contains("没有命中"), system);
    }

    @Test
    void providerFailureLandsAsErrorFrameNamingTheReasonAndSealsAsError() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenThrow(new ServiceException("未配置可用 LLM 供应商：请在系统设置-大模型清单启用至少一个端点",
                        BizErrorCode.AI_PROVIDER_ERROR));

        orchestrator.run(RUN);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("未配置可用 LLM 供应商"), error.getValue());
        verify(gateway).closeChatMessage(42L, "error");
    }

    @Test
    void streamParseBreakJournalsTheHumanInterruptSentenceWithoutRawTokens() {
        // The reported surface: the LLM client wrapped a parser failure
        // whose JDK message was the bare token "closed". The chat line must
        // name the class in plain words with the working resend door — the
        // raw token never reaches the screen.
        ChatRunOrchestrator orchestrator = harness("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenThrow(new ServiceException("AI 流式响应解析失败: closed",
                        BizErrorCode.AI_FORMAT_ERROR));

        orchestrator.run(RUN);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("中断"), error.getValue());
        assertTrue(error.getValue().contains("重发"), error.getValue());
        assertFalse(error.getValue().contains("closed"), error.getValue());
        assertFalse(error.getValue().contains("解析失败"), error.getValue());
        verify(gateway).closeChatMessage(42L, "error");
    }

    @Test
    void ledgerWriteFailureIsRethrownForBrokerRetryAndDeadLettering() {
        ChatRunOrchestrator orchestrator = harness("true");
        // the ledger door itself is down: neither content nor the error frame can be journaled
        doThrow(new ServiceException("core 账本门不可达", BizErrorCode.SERVICE_UNAVAILABLE))
                .when(gateway).appendChatFrame(anyLong(), anyLong(), any(), any(), any());
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenAnswer(invocation -> {
            ReActStreamSink sink = invocation.getArgument(6);
            sink.onDelta("半句");
            return outcome(1, 1);
        });

        // output that could not be journaled must not pretend success: the
        // exception surfaces so the container retries and the DLX keeps it
        org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> orchestrator.run(RUN));
        verify(gateway, never()).closeChatMessage(anyLong(), any());
    }

    // ---- knife 4c: consuming the @-mention chips (fetch → segment check → prompt) ----

    private static AiChatRunMessage runWithRefs(ChatObjectRef... refs) {
        return new AiChatRunMessage(9L, 42L, 3L, 7L, "帮我看看这个对象", List.of(refs), null, List.of());
    }

    private ChatCompletionRequest captureRequest(ChatRunOrchestrator orchestrator,
                                                 AiChatRunMessage run) {
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));
        orchestrator.run(run);
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), anyLong(), any(), anyLong(), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        return request.getValue();
    }

    private static String systemOf(ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(m -> "system".equals(m.role())).findFirst().orElseThrow().textContent();
    }

    @Test
    void pinnedCaseChipIsFetchedAndRidesTheSystemPromptAsReferenceBlock() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getCase(11L)).thenReturn(java.util.Map.of(
                "projectId", 3L, "title", "登录冒烟", "caseNo", "C-0001", "status", 2));

        ChatCompletionRequest request = captureRequest(orchestrator,
                runWithRefs(new ChatObjectRef("case", 11L, "登录冒烟")));

        String system = systemOf(request);
        assertTrue(system.contains("[R1]"), system);
        assertTrue(system.contains("登录冒烟"), system);
        assertTrue(system.contains("用户在本问题中钉住"), system);
        assertTrue(system.contains("—— 引用对象 ——"), system);
    }

    @Test
    void crossProjectChipRefusesTheRunNamingTheObjectAndSealsAsError() {
        ChatRunOrchestrator orchestrator = harness("true");
        // chip points at another project's row: a horizontal-access attempt (H1)
        when(gateway.getCase(12L)).thenReturn(java.util.Map.of(
                "projectId", 4L, "title", "他项目用例", "caseNo", "C-0002", "status", 2));

        orchestrator.run(runWithRefs(new ChatObjectRef("case", 12L, "他项目用例")));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("他项目用例"), error.getValue());
        verify(gateway).closeChatMessage(42L, "error");
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void vanishedRowChipRefusesTheRunNamingTheReference() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getBatch(5L)).thenThrow(
                new ServiceException("批次不存在", BizErrorCode.NOT_FOUND));

        orchestrator.run(runWithRefs(new ChatObjectRef("batch", 5L, "上周回归批")));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("上周回归批"), error.getValue());
        verify(gateway).closeChatMessage(42L, "error");
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void defectChipIsSliceCheckedThroughItsOwningCase() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getDefect(8L)).thenReturn(java.util.Map.of(
                "caseId", 11L, "defectKey", "PIKE-8", "defectStatus", "open"));
        when(gateway.getCase(11L)).thenReturn(java.util.Map.of(
                "projectId", 4L, "title", "他项目用例", "caseNo", "C-0002", "status", 2));

        orchestrator.run(runWithRefs(new ChatObjectRef("defect", 8L, "线上漏测缺陷")));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("线上漏测缺陷"), error.getValue());
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void docChipDigestUsesTheFetchedNameNotTheStaleChipTitle() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getDocument(6L)).thenReturn(java.util.Map.of(
                "projectId", 3L, "name", "接口规范 v2", "fileType", "md", "status", 2));

        ChatCompletionRequest request = captureRequest(orchestrator,
                runWithRefs(new ChatObjectRef("doc", 6L, "旧标题")));

        assertTrue(systemOf(request).contains("接口规范 v2"), systemOf(request));
    }

    // ---- knife 1 : the failure-badge execution context (diagnosis → prompt) ----

    private static AiChatRunMessage runWithExecution(Long executionId) {
        return new AiChatRunMessage(9L, 42L, 3L, 7L, "这次执行为什么失败", List.of(), executionId, List.of());
    }

    @Test
    void failedExecutionContextFetchesTheDiagnosisIntoTheSystemPrompt() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getRepairTaskView(55L)).thenReturn(
                new com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView(
                        3L, 11L, 4, "v2", "{\"actions\":[{\"type\":\"click\"}]}",
                        2, "元素未找到：#submit-order"));

        ChatCompletionRequest request = captureRequest(orchestrator, runWithExecution(55L));

        String system = systemOf(request);
        assertTrue(system.contains("—— 失败诊断 ——"), system);
        assertTrue(system.contains("元素未找到：#submit-order"), system);
        assertTrue(system.contains("click"), system);
    }

    @Test
    void crossProjectExecutionRefusesTheRunNamingTheSliceBreach() {
        ChatRunOrchestrator orchestrator = harness("true");
        // the badge rode in another project's task id: reading its diagnosis
        // would leak that project's error detail + script (H1 red line)
        when(gateway.getRepairTaskView(56L)).thenReturn(
                new com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView(
                        4L, 12L, 4, "v2", "{}", 2, "他项目的失败详情"));

        orchestrator.run(runWithExecution(56L));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("error"), error.capture(), isNull());
        assertTrue(error.getValue().contains("56"), error.getValue());
        verify(gateway).closeChatMessage(42L, "error");
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unreadableExecutionDegradesHonestlyAndTheTurnStillRuns() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getRepairTaskView(57L)).thenThrow(
                new ServiceException("执行任务不存在", BizErrorCode.NOT_FOUND));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(runWithExecution(57L));

        // honest state frame says the diagnosis could not be read (never a
        // silent drop — the user clicked a badge expecting that failure);
        // the grounding frame is also a state frame, so filter by payload
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateway, atLeast(2)).appendChatFrame(anyLong(), anyLong(),
                type.capture(), payload.capture(), any());
        boolean honestLine = false;
        for (int i = 0; i < type.getAllValues().size(); i++) {
            if ("state".equals(type.getAllValues().get(i))
                    && payload.getAllValues().get(i).contains("读不到")) {
                honestLine = true;
            }
        }
        assertTrue(honestLine, type.getAllValues().toString());
        // the turn still ran, and its prompt carries no fabricated diagnosis
        ArgumentCaptor<ChatCompletionRequest> request =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner).runStreaming(request.capture(), anyLong(), any(), anyLong(), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        assertTrue(!systemOf(request.getValue()).contains("—— 失败诊断 ——"),
                systemOf(request.getValue()));
    }

    @Test
    void plainQuestionWithoutExecutionContextStaysUntouched() {
        ChatRunOrchestrator orchestrator = harness("true");

        ChatCompletionRequest request = captureRequest(orchestrator, runWithExecution(null));

        assertTrue(!systemOf(request).contains("失败诊断"), systemOf(request));
        verify(gateway, never()).getRepairTaskView(anyLong());
    }

    // ---- knife 2 (emitter side): write-tool calls become CASE_WRITE draft
    // cards. The dialog chain NEVER executes an x-write tool (the dispatcher
    // refuses it token-less); the orchestrator scans the ReAct transcript for
    // the model's case.create / case.update call and journals a proposal card
    // whose meta is assembled server-side — the client can never forge it. ----

    private static final String CASE_TOOL_KEY = "ai.chat.case-draft.enabled";
    private static final String TAG_TOOL_KEY = "ai.chat.tag-draft.enabled";
    private static final String PPT_TOOL_KEY = "ai.chat.prompttpl-draft.enabled";
    private static final String SKILL_TOOL_KEY = "ai.chat.skill-draft.enabled";
    private static final String REVIEW_TOOL_KEY = "ai.chat.review-draft.enabled";
    private static final String PLAN_TOOL_KEY = "ai.chat.plan-draft.enabled";
    private static final String BATCH_TOOL_KEY = "ai.chat.batch-draft.enabled";
    private static final String REPORT_TOOL_KEY = "ai.chat.report-draft.enabled";
    private static final String APITEST_TOOL_KEY = "ai.chat.apitest-draft.enabled";
    private static final String APISPEC_TOOL_KEY = "ai.chat.apispec-draft.enabled";
    private static final String PERF_TOOL_KEY = "ai.chat.perf-draft.enabled";
    private static final String RESERVATION_TOOL_KEY = "ai.chat.reservation-draft.enabled";
    private static final String POPUP_RULE_TOOL_KEY = "ai.chat.popup-rule-draft.enabled";
    private static final String MOCK_SPACE_TOOL_KEY = "ai.chat.mock-space-draft.enabled";
    private static final String MOCK_STUB_TOOL_KEY = "ai.chat.mock-stub-draft.enabled";
    private static final String AIGEN_BATCH_TOOL_KEY = "ai.chat.aigen-batch-draft.enabled";
    private static final String DEFECT_TOOL_KEY = "ai.chat.defect-draft.enabled";
    private static final String DATA_FACTORY_TOOL_KEY = "ai.chat.datafactory-draft.enabled";
    private static final String EXPLORE_TOOL_KEY = "ai.chat.explore-draft.enabled";

    /**
 * Registry kinds the emitter is allowed to consult (tool-surface clearing
 * batch stage 2): the draft kind of a write tool comes from the
 * registry door, never from a name list the orchestrator memorises — that
 * hard-coding is exactly why only case.* could ever grow a card. Test
 * stubs feed the same shape the live /internal/tools/draft-kinds returns.
 */
    private void stubDraftKinds(
            com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind... kinds) {
        when(gateway.getDraftToolKinds(anyLong(), anyLong()))
                .thenReturn(java.util.List.of(kinds));
    }

    private static com.sixbean.testpike.aiservice.aichat.domain.component
            .DraftToolKind kind(String name, String objectType) {
        return new com.sixbean.testpike.aiservice.aichat.domain.component
                .DraftToolKind(name, objectType);
    }

    private static ReActOutcome outcomeWithToolCall(String toolName, String argumentsJson) {
        ChatMessage assistant = ChatMessage.assistantWithToolCalls("我来生成提案",
                List.of(new LlmToolCall("call-1", toolName, argumentsJson)));
        return new ReActOutcome(new LlmChatResult("答", "p", "m", 10, 5, 5L, null),
                List.of(assistant), 1, 0, 1, false, null);
    }

    private void stubCaseToolOutcome(ReActOutcome outcome) {
        when(gateway.getSysConfig(CASE_TOOL_KEY)).thenReturn("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome);
    }

    /** Capture every appendChatFrame call: each element = "type|payload|meta". */
    private List<String> journalFrames(ChatRunOrchestrator orchestrator, AiChatRunMessage run) {
        orchestrator.run(run);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway, atLeast(0)).appendChatFrame(anyLong(), anyLong(),
                type.capture(), payload.capture(), meta.capture());
        List<String> frames = new java.util.ArrayList<>();
        for (int i =0; i < type.getAllValues().size(); i++) {
            frames.add(type.getAllValues().get(i) + "|" + payload.getAllValues().get(i)
                    + "|" + meta.getAllValues().get(i));
        }
        return frames;
    }

    @Test
    void caseCreateToolCallBecomesOneCaseWriteDraftCardBeforeTheDoneFrame() {
        ChatRunOrchestrator orchestrator = harness("true");
        stubCaseToolOutcome(outcomeWithToolCall("case.create",
                "{\"title\":\"登录冒烟\",\"priority\":2,\"steps\":[{\"description\":\"打开首页\"}]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("draft card missing: " + frames));
        assertTrue(draft.contains("新建用例：登录冒烟"), draft);
        assertTrue(draft.contains("\"objectType\":\"CASE_WRITE\""), draft);
        assertTrue(draft.contains("登录冒烟"), draft);
        // actionJson rides as the model's raw argument body (escaped inside meta)
        assertTrue(draft.contains("steps"), draft);
        // the done frame + seal still land AFTER the card (sealed turns gain no frames)
        int draftIdx = frames.indexOf(draft);
        int doneIdx = -1;
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).startsWith("done|")) {
                doneIdx = i;
                break;
            }
        }
        assertTrue(draftIdx >= 0 && doneIdx > draftIdx, "done must follow draft: " + frames);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void caseUpdateCallWithoutAnchorsIsHonestAbsenceNoDraftFrame() {
        ChatRunOrchestrator orchestrator = harness("true");
        // an edit proposal without caseId+baseVersion cannot be applied safely —
        // half-shapes journal nothing (a card that cannot land would bait a click)
        stubCaseToolOutcome(outcomeWithToolCall("case.update", "{\"title\":\"改名\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void caseUpdateCallWithAnchorsCarriesCaseIdAndBaseVersionInMeta() {
        ChatRunOrchestrator orchestrator = harness("true");
        stubCaseToolOutcome(outcomeWithToolCall("case.update",
                "{\"caseId\":11,\"baseVersion\":3,\"priority\":1}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("draft card missing: " + frames));
        assertTrue(draft.contains("\"caseId\":11"), draft);
        assertTrue(draft.contains("\"baseVersion\":3"), draft);
        assertTrue(draft.contains("编辑用例 #11"), draft);
    }

    @Test
    void caseDraftSwitchOffKeepsTheAnswerAndNamesTheSettingPathAndKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(CASE_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("case.create", "{\"title\":\"登录冒烟\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(CASE_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void caseDraftMetaStaysSingleLineWhenTitleCarriesQuotesAndNewlines() {
        ChatRunOrchestrator orchestrator = harness("true");
        String arguments = "{\"title\":\"含\\\"引号\\\"与\\n换行的标题\"}";
        stubCaseToolOutcome(outcomeWithToolCall("case.create", arguments));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("draft card missing: " + frames));
        String meta = draft.substring(draft.indexOf('|', draft.indexOf('|') + 1) + 1);
        assertTrue(meta.indexOf('\n') < 0 && meta.indexOf('\r') < 0,
                "meta must stay one line for the SSE comment channel: " + meta);
        // the raw argument body survives the escape round-trip byte for byte
        try {
            com.fasterxml.jackson.databind.JsonNode parsed =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(meta);
            org.junit.jupiter.api.Assertions.assertEquals(arguments,
                    parsed.path("actionJson").asText(), "actionJson must be the model's raw body");
            org.junit.jupiter.api.Assertions.assertEquals("CASE_WRITE",
                    parsed.path("objectType").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("draft meta must stay parsable JSON: " + meta, e);
        }
    }

    @Test
    void plainAnswerWithoutToolCallsEmitsNoDraftFrame() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(CASE_TOOL_KEY)).thenReturn("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    // ---- stage 2: emitter generalisation — kind routing from the
    // registry, first new domain = tags. The case spectra above must stay
    // green UNCHANGED (regression anchor of the generalisation). ----

    @Test
    void tagCreateToolCallBecomesOneTagWriteDraftCard() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(TAG_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("tag.create",
                "{\"name\":\"支付回归\",\"type\":4}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("tag draft card missing: " + frames));
        assertTrue(draft.contains("新建标签：支付回归"), draft);
        assertTrue(draft.contains("\"objectType\":\"TAG_WRITE\""), draft);
        assertTrue(draft.contains("tag.create"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void tagRenameCallCarriesTagIdAsTargetAnchor() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(TAG_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("tag.rename",
                "{\"tagId\":15,\"newName\":\"核心链路\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("tag draft card missing: " + frames));
        assertTrue(draft.contains("\"targetId\":15"), draft);
        assertTrue(draft.contains("标签 #15"), draft);
    }

    @Test
    void tagMergeCallAnchorsFromTagAndKeepsToTagInBody() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(TAG_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("tag.merge",
                "{\"fromTagId\":7,\"toTagId\":9}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("tag draft card missing: " + frames));
        assertTrue(draft.contains("\"targetId\":7"), draft);
        assertTrue(draft.contains("合并标签 #7"), draft);
        assertTrue(draft.contains("toTagId"), draft);
    }

    @Test
    void tagCallWithoutLandableShapeIsHonestAbsence() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(TAG_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("tag.create", "{}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void tagDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(TAG_TOOL_KEY)).thenReturn("false");
        when(gateway.getSysConfig(CASE_TOOL_KEY)).thenReturn("true");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("tag.create", "{\"name\":\"支付回归\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(TAG_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the tag key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void promptTplSaveCallBecomesOnePromptTplWriteDraftCard() {
        // stage 3a: /prompt-templates domain through the registry — the
        // emitter must card promptTpl.save with zero orchestrator literals.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PPT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("promptTpl.save",
                "{\"name\":\"用例生成骨架\",\"domain\":\"CASE_GEN\",\"content\":\"你是测试工程师…\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("prompt template draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"PROMPT_TPL_WRITE\""), draft);
        assertTrue(draft.contains("用例生成骨架"), draft);
        assertTrue(draft.contains("CASE_GEN"), draft);
        assertTrue(draft.contains("promptTpl.save"), draft);
        // Versioned templates have no pre-existing anchor: the card is a create.
        assertTrue(draft.contains("\"targetId\":0"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void promptTplSaveWithoutNameOrContentIsHonestAbsence() {
        // A half-shape card would bait a confirmation click that must fail at
        // the save door (name/domain/content are all required) — no card.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PPT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("promptTpl.save",
                "{\"domain\":\"CASE_GEN\",\"content\":\"缺名字的骨架\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void promptTplDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PPT_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("promptTpl.save",
                        "{\"name\":\"骨架\",\"domain\":\"CASE_GEN\",\"content\":\"正文\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(PPT_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the prompt-template key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void skillSaveCallBecomesOneSkillWriteDraftCard() {
        // stage 3b: /skills domain through the registry — the emitter
        // must card skill.save with zero orchestrator literals.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SKILL_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("skill.save",
                "{\"name\":\"用例评审规范\",\"type\":1,\"body\":\"检查步骤是否可回放…\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("skill draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"SKILL_WRITE\""), draft);
        assertTrue(draft.contains("用例评审规范"), draft);
        assertTrue(draft.contains("skill.save"), draft);
        // A new skill has no pre-existing anchor: the card is a create.
        assertTrue(draft.contains("\"targetId\":0"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void skillSaveWithoutNameOrBodyIsHonestAbsence() {
        // A half-shape card would bait a confirmation click that must fail at
        // the save door (name/type/body are all required) — no card.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SKILL_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("skill.save",
                "{\"type\":1,\"body\":\"缺名字的规范\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void skillDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SKILL_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("skill.save",
                        "{\"name\":\"规范\",\"type\":1,\"body\":\"正文\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(SKILL_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the skill-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void caseReviewCallBecomesOneReviewDraftCard() {
        // stage 3c: /review domain through the registry — the emitter
        // must card case.review with zero orchestrator literals. A review
        // card is anchored on the case row and its optimistic version.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REVIEW_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("case.review",
                "{\"caseId\":88,\"action\":\"reject\",\"remark\":\"步骤不可回放\","
                        + "\"expectedVersion\":3,\"feedbackTypes\":[\"step_inaccurate\"]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("review draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"CASE_REVIEW\""), draft);
        assertTrue(draft.contains("case.review"), draft);
        assertTrue(draft.contains("\"targetId\":88"), draft);
        assertTrue(draft.contains("\"baseVersion\":3"), draft);
        assertTrue(draft.contains("拒绝"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void caseReviewWithoutFeedbackTypesIsHonestAbsence() {
        // reject/need_change require feedbackTypes at the save door — a card
        // without them would bait a confirmation click that must fail. Pass
        // needs none, so only the reject spectrum stays card-less here.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REVIEW_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("case.review",
                "{\"caseId\":88,\"action\":\"reject\",\"remark\":\"缺问题类型\","
                        + "\"expectedVersion\":3}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void reviewDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REVIEW_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("case.review",
                        "{\"caseId\":88,\"action\":\"pass\",\"expectedVersion\":3}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(REVIEW_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the review-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void planCreateCallBecomesOnePlanDraftCard() {
        // stage 4a: /plans domain through the registry — the emitter must
        // card plan.create with zero orchestrator literals. A plan create is
        // always a create (no pre-existing anchor): the new row id is pinned
        // back onto the ledger when the write lands.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PLAN_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("plan.create",
                "{\"name\":\"发布回归计划\",\"targetVersion\":\"v2.3\","
                        + "\"envIds\":[11,12],\"caseScope\":{\"phases\":[3],\"priorities\":[0,1]}}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("plan draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"PLAN_WRITE\""), draft);
        assertTrue(draft.contains("plan.create"), draft);
        assertTrue(draft.contains("发布回归计划"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void planCreateWithoutScopeDimensionsIsHonestAbsence() {
        // The save door refuses a plan whose scope has no dimension (it would
        // pull every case in the project) — a card that must fail on confirm
        // is banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PLAN_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("plan.create",
                "{\"name\":\"空范围计划\",\"envIds\":[11],\"caseScope\":{}}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void planDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PLAN_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("plan.create",
                        "{\"name\":\"回归计划\",\"envIds\":[11],"
                                + "\"caseScope\":{\"phases\":[1]}}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(PLAN_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the plan-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void batchCreateCallBecomesOneBatchDraftCard() {
        // stage 4b: /batches domain through the registry — the emitter
        // must card batch.create with zero orchestrator literals. A batch
        // create is always a create (no pre-existing anchor): the new row id
        // is pinned back onto the ledger when the write lands.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BATCH_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("batch.create",
                "{\"name\":\"登录模块回归批\",\"envId\":11,\"caseIds\":[101,102,103]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("batch draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"BATCH_WRITE\""), draft);
        assertTrue(draft.contains("batch.create"), draft);
        assertTrue(draft.contains("登录模块回归批"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void batchCreateWithoutCasesIsHonestAbsence() {
        // The save door refuses a batch with no case selected ("select at
        // least one case") — a card that must fail on confirmation is banned
        // here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BATCH_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("batch.create",
                "{\"name\":\"空批次\",\"envId\":11,\"caseIds\":[]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void batchDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(BATCH_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("batch.create",
                        "{\"name\":\"回归批\",\"envId\":11,\"caseIds\":[101]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(BATCH_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the batch-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void reportGenerateCallBecomesOneReportDraftCard() {
        // stage 4c: /test-report domain through the registry — the emitter
        // must card report.generate with zero orchestrator literals. A report
        // generate is always a create (the aggregation row is new): the new
        // report id is pinned back onto the ledger when the write lands.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REPORT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("report.generate",
                "{\"rangeType\":1,\"planId\":7,\"title\":\"登录模块总结报告\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("report draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"REPORT_WRITE\""), draft);
        assertTrue(draft.contains("report.generate"), draft);
        assertTrue(draft.contains("登录模块总结报告"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void reportGenerateWithoutRangeIsHonestAbsence() {
        // The save door refuses a plan-range report with no plan picked and a
        // time-range report with no window — a card that must fail on
        // confirmation is banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REPORT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("report.generate",
                "{\"rangeType\":2,\"title\":\"缺时间窗的报告\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void reportDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(REPORT_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("report.generate",
                        "{\"rangeType\":1,\"planId\":7}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(REPORT_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the report-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void apitestDefaultsCallBecomesOneDraftCard() {
        // stage 5a: /api-test domain through the registry — the emitter
        // must card apitest.defaults.save with zero orchestrator literals.
        // Saving collection defaults is always an update of an existing
        // collection: the payload must name the collection it targets.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APITEST_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("apitest.defaults.save",
                "{\"collectionId\":7,\"headersJson\":\"[{\\\"name\\\":\\\"X-Tenant\\\",\\\"value\\\":\\\"a\\\"}]\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("apitest draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"APITEST_WRITE\""), draft);
        assertTrue(draft.contains("apitest.defaults.save"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void apitestDefaultsWithoutTargetOrContentIsHonestAbsence() {
        // The save door refuses a collection that does not exist and a body
        // that saves nothing — a card that must fail on confirmation is
        // banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APITEST_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("apitest.defaults.save",
                "{\"headersJson\":\"[]\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void apitestDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APITEST_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("apitest.defaults.save",
                        "{\"collectionId\":7,\"authJson\":\"{}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(APITEST_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the apitest-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void apiSpecPullCallBecomesOneDraftCard() {
        // stage 5b: /api-inventory domain through the registry — the
        // emitter must card apiSpec.pull with zero orchestrator literals.
        // Pulling a contract through the URL entry is always an append onto
        // the project's version chain: the payload must name both the spec
        // name and the document url, the owning project comes from the
        // session and never from the model.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APISPEC_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("apiSpec.pull",
                "{\"name\":\"订单中心契约\",\"url\":\"https://internal.example/openapi.json\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("api-spec draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"APISPEC_WRITE\""), draft);
        assertTrue(draft.contains("apiSpec.pull"), draft);
        assertTrue(draft.contains("订单中心契约"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void apiSpecPullWithoutNameOrUrlIsHonestAbsence() {
        // The fetch chain refuses a blank contract name and a blank url —
        // a card that must fail on confirmation is banned here like in
        // every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APISPEC_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("apiSpec.pull",
                "{\"name\":\"缺地址的契约\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void apiSpecDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(APISPEC_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("apiSpec.pull",
                        "{\"name\":\"订单中心契约\",\"url\":\"https://internal.example/openapi.json\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(APISPEC_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the api-spec-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void perfLaunchCallBecomesOneDraftCardWithItsCostStatement() {
        // stage 5c: /perf domain through the registry. Launching pressure
        // is the heaviest write face in the dialog: the card must carry the
        // cost statement (environment occupancy + the gates that still own
        // the decision), because the emitter-side gate can only be a notice —
        // the launch transaction's safety gate owns refusal.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PERF_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("perf.run.launch",
                "{\"scenarioId\":7}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("perf launch draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"PERF_LAUNCH\""), draft);
        assertTrue(draft.contains("perf.run.launch"), draft);
        assertTrue(draft.contains("场景 #7"), draft);
        // Cost statement (U-5 summary-card wording family): the operator reads
        // what the run occupies before confirming.
        assertTrue(draft.contains("环境") && draft.contains("占用"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void perfLaunchWithoutScenarioAnchorIsHonestAbsence() {
        // The launch chain refuses an unnamed scenario — a card that must fail
        // on confirmation is banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PERF_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("perf.run.launch",
                "{\"envId\":3}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void perfDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PERF_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("perf.run.launch", "{\"scenarioId\":7}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(PERF_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the perf-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void reservationCreateCallBecomesOneDraftCardWithItsWindowStatement() {
        // stage 6a: /appauto/reservations domain through the registry.
        // A reservation holds one device for a time window: the card must
        // state the occupied device and window up front, because the
        // conflict decision (who else holds the device in that window) is
        // re-taken inside the SAME create transaction at landing time.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(RESERVATION_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("appauto.reservation.create",
                "{\"deviceId\":9,\"startsAt\":\"2026-10-30T09:00:00\","
                        + "\"endsAt\":\"2026-10-30T11:00:00\",\"reason\":\"回归验证\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("reservation draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"RESERVATION_WRITE\""), draft);
        assertTrue(draft.contains("appauto.reservation.create"), draft);
        assertTrue(draft.contains("设备 #9"), draft);
        // Cost statement (U-5 summary-card wording family): device occupancy
        // and the window are readable before confirming.
        assertTrue(draft.contains("占用") && draft.contains("09:00")
                && draft.contains("11:00"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void reservationCreateWithoutDeviceOrWindowAnchorIsHonestAbsence() {
        // The create chain refuses an unnamed device or window — a card that
        // must fail on confirmation is banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(RESERVATION_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("appauto.reservation.create",
                "{\"reason\":\"回归验证\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void reservationDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(RESERVATION_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("appauto.reservation.create",
                        "{\"deviceId\":9,\"startsAt\":\"2026-10-30T09:00:00\","
                                + "\"endsAt\":\"2026-10-30T11:00:00\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(RESERVATION_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the reservation-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void popupRuleLearnCallBecomesOneDraftCardWithItsPendingApprovalStatement() {
        // stage 6b: /appauto/popup-rules domain through the registry.
        // Learning reads one finished task's watch frames and produces a
        // PENDING rule draft — the card must state that nothing enters the
        // rule library until a human approves it on the approval view,
        // because that two-layer gate is re-taken inside the SAME learn
        // chain at landing time.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(POPUP_RULE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("appauto.popup-rule.learn",
                "{\"taskId\":77}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("popup-rule draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"POPUP_RULE_LEARN\""), draft);
        assertTrue(draft.contains("appauto.popup-rule.learn"), draft);
        assertTrue(draft.contains("任务 #77"), draft);
        // Cost statement: the learned rule lands as a pending draft the human
        // approves — the card says so before confirming.
        assertTrue(draft.contains("待批准") && draft.contains("批准"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void popupRuleLearnWithoutTaskAnchorIsHonestAbsence() {
        // The learn chain refuses an unnamed task (no frames to read) — a
        // card that must fail on confirmation is banned here like in every
        // other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(POPUP_RULE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("appauto.popup-rule.learn",
                "{\"reason\":\"沉淀弹窗规则\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void popupRuleDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(POPUP_RULE_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("appauto.popup-rule.learn",
                        "{\"taskId\":77}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(POPUP_RULE_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the popup-rule-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void mockSpaceCreateCallBecomesOneDraftCardWithItsDraftStateStatement() {
        // stage 7: /mock/spaces domain through the registry. A created
        // space is always a DRAFT row (status = 0) — publishing it (the
        // transition that opens the routing door) stays a deliberate human
        // click on /mock/spaces, so the card must state that up front.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_SPACE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.space.create",
                "{\"name\":\"订单联调空间\",\"spaceKey\":\"orders\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("mock-space draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"MOCK_SPACE_WRITE\""), draft);
        assertTrue(draft.contains("mock.space.create"), draft);
        assertTrue(draft.contains("订单联调空间") && draft.contains("orders"), draft);
        // Cost statement: the row lands as a draft; publishing stays human.
        assertTrue(draft.contains("草稿") && draft.contains("发布"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockSpaceCreateWithoutNameOrKeyAnchorIsHonestAbsence() {
        // The save chain refuses a space without a name or a routing key — a
        // card that must fail on confirmation is banned here like in every
        // other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_SPACE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.space.create",
                "{\"name\":\"订单联调空间\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockSpaceDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_SPACE_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("mock.space.create",
                        "{\"name\":\"订单联调空间\",\"spaceKey\":\"orders\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(MOCK_SPACE_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the mock-space-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void mockStubCreateCallBecomesOneDraftCardStatingTheServingGate() {
        // stage 8: /mock/endpoints domain through the registry. A stub
        // row serves traffic only while its owning space is published and the
        // rule is enabled, so the card must state the serving gate up front.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_STUB_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.stub.create",
                "{\"spaceId\":9,\"name\":\"订单查询打桩\",\"method\":\"GET\",\"pathPattern\":\"/orders/{id}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("mock-stub draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"MOCK_STUB_WRITE\""), draft);
        assertTrue(draft.contains("mock.stub.create"), draft);
        assertTrue(draft.contains("订单查询打桩") && draft.contains("/orders/{id}"), draft);
        // Cost statement: the row only serves under a published space.
        assertTrue(draft.contains("发布") && draft.contains("提供访问"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockStubCreateWithoutSpaceNameMethodOrPathAnchorIsHonestAbsence() {
        // The save chain refuses a rule without a name, method or path and the
        // space anchor is where the row gets filed — a card that must fail on
        // confirmation is banned here like in every other shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_STUB_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.stub.create",
                "{\"name\":\"订单查询打桩\",\"method\":\"GET\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockStubEditWithAnchorsBecomesOneDraftCardOnTheEditKind() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_STUB_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.stub.update",
                "{\"stubId\":77,\"baseVersion\":3,\"name\":\"订单查询打桩v2\",\"method\":\"GET\",\"pathPattern\":\"/orders/{id}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("mock-stub edit draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"MOCK_STUB_WRITE\""), draft);
        assertTrue(draft.contains("\"targetId\":77") && draft.contains("\"baseVersion\":3"), draft);
        assertTrue(draft.contains("订单查询打桩v2"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockStubEditWithoutStubIdOrVersionAnchorIsHonestAbsence() {
        // An edit without the row anchor or the optimistic echo would land as
        // a lost update or a 404 — no card is emitted for a half anchor.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_STUB_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("mock.stub.update",
                "{\"name\":\"订单查询打桩v2\",\"method\":\"GET\",\"pathPattern\":\"/orders/{id}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void mockStubDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(MOCK_STUB_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("mock.stub.create",
                        "{\"spaceId\":9,\"name\":\"订单查询打桩\",\"method\":\"GET\",\"pathPattern\":\"/orders/{id}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(MOCK_STUB_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the mock-stub-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void writeToolWithNoRegisteredDraftKindStaysHonestNoCard() {
        // A tool the registry does not route to any draft kind cannot produce
        // a card: the dispatcher refused its token-less call, and inventing a
        // kind server-side would journal a card no strategy can land.
        ChatRunOrchestrator orchestrator = harness("true");
        stubDraftKinds(kind("case.create", "CASE_WRITE"));
        stubCaseToolOutcome(outcomeWithToolCall("plan.create", "{\"title\":\"冒烟计划\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void registryDoorFailureNeverBreaksTheAnswerTurn() {
        // The draft card is best-effort on top of the answer: if the kind door
        // is down the turn still seals done, simply without a card.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getDraftToolKinds(anyLong(), anyLong())).thenThrow(
                new ServiceException("core 不可达", BizErrorCode.INTERNAL_ERROR));
        stubCaseToolOutcome(outcomeWithToolCall("case.create", "{\"title\":\"登录冒烟\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    // ---- knife 1 : the failure-scene screenshot seam ----

    private ChatRunOrchestrator harnessWithScene(String execEnabled,
            FailureScreenshotResolver sceneResolver) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn(execEnabled);
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean())).thenReturn(GroundingOutcome.none());
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                sceneResolver, null, null, null,
                new com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver(
                        gateway, new com.sixbean.testpike.aiservice.aichat.domain.component
                                .AiCtxProperties()),
                null);
    }

    private void stubResolvedTask55() {
        when(gateway.getRepairTaskView(55L)).thenReturn(
                new com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView(
                        3L, 11L, 4, "v2", "{\"actions\":[{\"type\":\"click\"}]}",
                        2, "元素未找到：#submit-order"));
    }

    @Test
    void attachedSceneRidesTheSystemPromptAndOneStateFrameNamesTheArtifact() {
        FailureScreenshotResolver scene = mock(FailureScreenshotResolver.class);
        when(scene.resolve(anyLong(), any())).thenReturn(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .FailureScreenshotEvidence.attached(
                                "页面停留在订单列表，弹窗提示服务超时", "proj/7/screenshot/f.png"));
        ChatRunOrchestrator orchestrator = harnessWithScene("true", scene);
        stubResolvedTask55();

        ChatCompletionRequest request = captureRequest(orchestrator, runWithExecution(55L));

        String system = systemOf(request);
        assertTrue(system.contains("—— 截图场景观察 ——"), system);
        assertTrue(system.contains("弹窗提示服务超时"), system);
        List<String> frames = journalFrames(
                harnessWithScene("true", scene), runWithExecution(55L));
        assertTrue(frames.stream().anyMatch(f -> f.startsWith("state|")
                && f.contains("proj/7/screenshot/f.png")), frames.toString());
    }

    @Test
    void switchOffSceneIsSilentNoFrameNoBlockAndPromptUnchanged() {
        FailureScreenshotResolver scene = mock(FailureScreenshotResolver.class);
        when(scene.resolve(anyLong(), any())).thenReturn(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .FailureScreenshotEvidence.NONE);
        ChatRunOrchestrator orchestrator = harnessWithScene("true", scene);
        stubResolvedTask55();

        ChatCompletionRequest request = captureRequest(orchestrator, runWithExecution(55L));

        String system = systemOf(request);
        assertTrue(system.contains("—— 失败诊断 ——"), system);
        assertFalse(system.contains("截图场景观察"), system);
        List<String> frames = journalFrames(
                harnessWithScene("true", scene), runWithExecution(55L));
        long stateFrames = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains("截图")).count();
        assertEquals(0, stateFrames, frames.toString());
    }

    @Test
    void degradedSceneEmitsOneHonestStateFrameAndTheTextOnlyTurnStillRuns() {
        FailureScreenshotResolver scene = mock(FailureScreenshotResolver.class);
        when(scene.resolve(anyLong(), any())).thenReturn(
                com.sixbean.testpike.aiservice.aichat.domain.component
                        .FailureScreenshotEvidence.absent("该执行记录没有可用的失败现场截图产物"));
        ChatRunOrchestrator orchestrator = harnessWithScene("true", scene);
        stubResolvedTask55();
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        List<String> frames = journalFrames(orchestrator, runWithExecution(55L));

        assertTrue(frames.stream().anyMatch(f -> f.startsWith("state|")
                && f.contains("没有可用的失败现场截图")), frames.toString());
        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void aiGenBatchSubmitCallBecomesOneDraftCardStatingTheReviewGate() {
        // stage 9: /ai/generate domain through the registry. Submitting
        // only QUEUES asynchronous generation — produced cases still run
        // through the case review state machine, so the card must state the
        // queueing gate and the never-auto-approve boundary up front.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(AIGEN_BATCH_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("aiGen.batch.submit",
                "{\"requirements\":[\"订单创建成功后库存扣减\",\"库存不足时下单被拒绝\"]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("ai-gen batch draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"AIGEN_BATCH_WRITE\""), draft);
        assertTrue(draft.contains("aiGen.batch.submit"), draft);
        assertTrue(draft.contains("2 条") && draft.contains("排队"), draft);
        assertTrue(draft.contains("评审"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void aiGenBatchSubmitWithoutUsableRequirementTextIsHonestAbsence() {
        // The enqueue chain refuses an empty list and every blank line, so a
        // card built from blank requirements must fail on confirmation —
        // honest absence, same ban as every other half shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(AIGEN_BATCH_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("aiGen.batch.submit",
                "{\"requirements\":[\"   \",\"\"]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void aiGenBatchDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(AIGEN_BATCH_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("aiGen.batch.submit",
                        "{\"requirements\":[\"登录失败五次锁定账号\"]}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(AIGEN_BATCH_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the aigen-batch-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void defectCreateCallBecomesOneDraftCardStatingTheProjectAndStateMachine() {
        // stage 10: /defects domain through the registry. A create card
        // must name the title (the object being filed) and state that the
        // owning project comes from the session plus that the row lands in
        // the initial state the human then moves through the machine.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("defect.create",
                "{\"projectId\":999,\"title\":\"下单后库存未扣减\",\"severity\":1}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("defect create draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"DEFECT_WRITE\""), draft);
        assertTrue(draft.contains("defect.create"), draft);
        assertTrue(draft.contains("下单后库存未扣减"), draft);
        assertTrue(draft.contains("缺陷库"), draft);
        assertTrue(draft.contains("新建"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void defectEditWithoutRowAndVersionAnchorsIsHonestAbsence() {
        // An edit card without the row it edits AND the version the model
        // read cannot guarantee the optimistic anchor — the save door would
        // take the row's live version and silently race whoever edited in
        // between. Honest absence, the same ban as every other half shape.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("defect.update",
                "{\"defectId\":0,\"title\":\"缺版本锚\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void defectCloseWithoutReasonOrWithIllegalTargetStateIsHonestAbsence() {
        // Two illegal transition shapes in one turn each: closing without a
        // reason (the chain refuses it) and a target state outside the
        // machine. Neither may become a confirmable card.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("defect.transition",
                "{\"defectId\":7,\"toStatus\":5,\"version\":3}"));

        List<String> noReason = journalFrames(orchestrator, RUN);
        assertTrue(noReason.stream().noneMatch(f -> f.startsWith("draft|")), noReason.toString());

        ChatRunOrchestrator second = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("defect.transition",
                "{\"defectId\":7,\"toStatus\":9,\"closedReason\":\"复验通过\",\"version\":3}"));

        List<String> illegalState = journalFrames(second, RUN);
        assertTrue(illegalState.stream().noneMatch(f -> f.startsWith("draft|")), illegalState.toString());
    }

    @Test
    void defectTransitionCardNamesTheRowAndTheTargetState() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("defect.transition",
                "{\"defectId\":7,\"toStatus\":5,\"closedReason\":\"复验通过，问题不再重现\",\"version\":3}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("defect transition draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"DEFECT_WRITE\""), draft);
        assertTrue(draft.contains("#7"), draft);
        assertTrue(draft.contains("已关闭"), draft);
        // The optimistic anchor the model saw travels on the card so a stale
        // echo refuses landing with the plain-word conflict.
        assertTrue(draft.contains("第 3 版"), draft);
    }

    @Test
    void defectDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DEFECT_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("defect.create",
                        "{\"title\":\"支付回调重复入账\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(DEFECT_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the defect-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void dataFactoryCreateCallBecomesOneDraftCardStatingTheSharedSaveChain() {
        // stage 11: /data-factories domain through the registry. A
        // create card must name the script (the object being filed) and
        // state that the owning project comes from the session plus that
        // the type-one-of, env-in-project and pool-key uniqueness gates are
        // re-decided by the same save chain on confirmation.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.create",
                "{\"projectId\":999,\"envId\":5,\"type\":2,\"name\":\"批量生成测试订单\","
                        + "\"content\":\"{\\\"poolType\\\":\\\"order\\\",\\\"poolKey\\\":\\\"order_nos\\\",\\\"sql\\\":\\\"SELECT 1\\\"}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("data-factory create draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"DATA_FACTORY_WRITE\""), draft);
        assertTrue(draft.contains("datafactory.create"), draft);
        assertTrue(draft.contains("批量生成测试订单"), draft);
        assertTrue(draft.contains("数据工厂"), draft);
        assertTrue(draft.contains("会话项目"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void dataFactoryCreateWithoutNameOrContentIsHonestAbsence() {
        // A create card missing its name (and/or the script body) could only
        // ever fail on confirmation — the save chain refuses both — so it is
        // banned here instead of baiting a confirm click.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.create",
                "{\"envId\":5,\"type\":2,\"content\":\"{}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());

        ChatRunOrchestrator second = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.create",
                "{\"envId\":5,\"type\":2,\"name\":\"缺内容\"}"));

        List<String> noContent = journalFrames(second, RUN);
        assertTrue(noContent.stream().noneMatch(f -> f.startsWith("draft|")), noContent.toString());
        verify(gateway, org.mockito.Mockito.times(2)).closeChatMessage(42L, "done");
    }

    @Test
    void dataFactoryCreateWithIllegalTypeOrMissingEnvIsHonestAbsence() {
        // The emitter bans the shapes the chain is guaranteed to refuse: a
        // type outside 1/2/3 and a create without its env anchor.
        ChatRunOrchestrator badType = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.create",
                "{\"envId\":5,\"type\":9,\"name\":\"非法类型\",\"content\":\"{}\"}"));

        List<String> frames = journalFrames(badType, RUN);
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());

        ChatRunOrchestrator noEnv = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.create",
                "{\"type\":2,\"name\":\"缺环境\",\"content\":\"{}\"}"));

        List<String> envFrames = journalFrames(noEnv, RUN);
        assertTrue(envFrames.stream().noneMatch(f -> f.startsWith("draft|")), envFrames.toString());
        verify(gateway, org.mockito.Mockito.times(2)).closeChatMessage(42L, "done");
    }

    @Test
    void dataFactoryEditCardNamesTheRowAndTheSharedSaveChain() {
        // An edit anchors on the existing script row; tp_data_factory has no
        // version ledger (updated in place like the api-test kind), so the
        // card carries target_id and base_version stays 0.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.update",
                "{\"factoryId\":12,\"name\":\"订单造数脚本 v2\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("data-factory edit draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"DATA_FACTORY_WRITE\""), draft);
        assertTrue(draft.contains("#12"), draft);
        assertTrue(draft.contains("订单造数脚本 v2"), draft);
        assertTrue(draft.contains("启用/停用"), draft);
    }

    @Test
    void dataFactoryEditWithoutRowAnchorIsHonestAbsence() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("datafactory.update",
                "{\"name\":\"没有目标脚本锚\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void dataFactoryDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(DATA_FACTORY_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("datafactory.create",
                        "{\"envId\":5,\"type\":2,\"name\":\"订单造数\",\"content\":\"{}\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(DATA_FACTORY_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the datafactory-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    @Test
    void exploreCreateCallBecomesOneDraftCardStatingTheSharedCreateChain() {
        // stage 12: /explore domain through the registry (last debt
        // leaf). A card must name the goal (the session being filed), state
        // that the owning project comes from the session, and declare the
        // cost side up front — confirming the card starts the exploration
        // and dials the budget guard, the SSRF floor and the env whitelist
        // through the SAME create chain the explore page runs.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(EXPLORE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("explore.session.create",
                "{\"projectId\":999,\"goalText\":\"逛一遍下单主流程找出报错页面\","
                        + "\"startUrl\":\"https://shop.example.com\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);

        String draft = frames.stream().filter(f -> f.startsWith("draft|")).findFirst()
                .orElseThrow(() -> new AssertionError("explore create draft card missing: " + frames));
        assertTrue(draft.contains("\"objectType\":\"EXPLORE_CREATE\""), draft);
        assertTrue(draft.contains("explore.session.create"), draft);
        assertTrue(draft.contains("逛一遍下单主流程找出报错页面"), draft);
        assertTrue(draft.contains("会话项目"), draft);
        assertTrue(draft.contains("预算"), draft);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void exploreCreateWithoutGoalOrStartUrlIsHonestAbsence() {
        // The create chain refuses a blank goal and a blank start URL, so a
        // card missing either could only ever bait a failed confirmation —
        // banned here instead.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(EXPLORE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("explore.session.create",
                "{\"startUrl\":\"https://shop.example.com\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());

        ChatRunOrchestrator second = harness("true");
        when(gateway.getSysConfig(EXPLORE_TOOL_KEY)).thenReturn("true");
        stubCaseToolOutcome(outcomeWithToolCall("explore.session.create",
                "{\"goalText\":\"缺起点\"}"));

        List<String> noUrl = journalFrames(second, RUN);
        assertTrue(noUrl.stream().noneMatch(f -> f.startsWith("draft|")), noUrl.toString());
        verify(gateway, org.mockito.Mockito.times(2)).closeChatMessage(42L, "done");
    }

    @Test
    void exploreDraftSwitchOffNamesItsOwnSettingKey() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(EXPLORE_TOOL_KEY)).thenReturn("false");
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class)))
                .thenReturn(outcomeWithToolCall("explore.session.create",
                        "{\"goalText\":\"逛一遍下单主流程\",\"startUrl\":\"https://shop.example.com\"}"));

        List<String> frames = journalFrames(orchestrator, RUN);
        assertTrue(frames.stream().noneMatch(f -> f.startsWith("draft|")), frames.toString());
        String guidance = frames.stream()
                .filter(f -> f.startsWith("state|") && f.contains(EXPLORE_TOOL_KEY))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "R32 guidance frame naming the explore-draft key missing: " + frames));
        assertTrue(guidance.contains("系统管理") && guidance.contains("系统配置"), guidance);
    }

    // ---- phase 1: the session token budget gate (turn boundary, R32 guidance) ----

    private static final String SESSION_BUDGET_KEY = "ai.chat.session-budget-tokens";

    @Test
    void sessionOverBudgetRefusesTheTurnNamingKeyPathAndConsumption() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SESSION_BUDGET_KEY)).thenReturn("200000");
        when(gateway.sessionTotalTokens(9L)).thenReturn(200001L);

        List<String> frames = journalFrames(orchestrator, RUN);

        String error = frames.stream().filter(f -> f.startsWith("error|")).findFirst()
                .orElseThrow(() -> new AssertionError("budget refusal frame missing: " + frames));
        // R32 posture: the refusal names the setting path and the key verbatim,
        // plus the real consumption number the user can reconcile in the cost view.
        assertTrue(error.contains(SESSION_BUDGET_KEY), error);
        assertTrue(error.contains("系统管理") && error.contains("系统配置"), error);
        assertTrue(error.contains("200001"), error);
        verify(gateway).closeChatMessage(42L, "error");
        verify(runner, never()).runStreaming(any(), anyLong(), any(), any(), any(), any(), any(), any());
        // The gate reads the ledger once per turn — never inside the frame loop.
        verify(gateway, times(1)).sessionTotalTokens(9L);
    }

    @Test
    void sessionUnderBudgetRunsTheTurnNormally() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SESSION_BUDGET_KEY)).thenReturn("200000");
        when(gateway.sessionTotalTokens(9L)).thenReturn(199999L);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void unsetOrGarbageBudgetMeansUnlimited() {
        // Key absent (harness leaves getSysConfig unstubbed = null): no gate.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.sessionTotalTokens(9L)).thenReturn(999_999_999L);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);
        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void garbageBudgetValueIsIgnoredInsteadOfRefusingEveryTurn() {
        // A typed garbage value ("abc") must NOT be read as zero — zero would
        // silently refuse every question. Same fail-open posture as absent.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SESSION_BUDGET_KEY)).thenReturn("abc");
        when(gateway.sessionTotalTokens(9L)).thenReturn(50_000L);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);
        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void zeroBudgetTurnLedgerStillRefusesOnlyWhenExceeded() {
        // Boundary: consumed exactly at the cap still runs (the gate is
        // strictly-greater); one token over refuses.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SESSION_BUDGET_KEY)).thenReturn("200000");
        when(gateway.sessionTotalTokens(9L)).thenReturn(200000L);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);
        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
    }

    @Test
    void budgetLedgerDoorDownLetsTheTurnProceed() {
        // Fail-open on the READ path: a billing read that cannot answer must
        // not take the dialog down — the ledger catches up next turn.
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(SESSION_BUDGET_KEY)).thenReturn("200000");
        when(gateway.sessionTotalTokens(9L)).thenThrow(
                new ServiceException("core 不可达", BizErrorCode.SERVICE_UNAVAILABLE));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        verify(runner).runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        verify(gateway).closeChatMessage(42L, "done");
    }

    /**
 * Cite meta exposes the ledger revision : a knowledge
 * citation names the version of the document that backed the answer, and a
 * help citation (no ledger row) serializes "revision":null.
 */
    @Test
    void citeFrameMetaExposesLedgerRevisionForKnowledgeCitations() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(grounding.assemble(eq(3L), eq(RUN.prompt()), anyBoolean())).thenReturn(new GroundingOutcome(
                List.of(new GroundingCitation(1, "knowledge", 88L, null, "压测口径说明",
                                "并发档位含义", 0.7, 5, null),
                        new GroundingCitation(2, "help", 77L, "apitest", "接口测试指南",
                                "集合与请求的编排在左侧列表", 0.9)),
                "[C1] 压测口径说明: 并发档位含义\n", false));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(outcome(10, 5));

        orchestrator.run(RUN);

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).appendChatFrame(eq(9L), eq(42L), eq("cite"), any(),
                meta.capture());
        assertTrue(meta.getAllValues().get(0).contains("\"revision\":5"),
                meta.getAllValues().get(0));
        assertTrue(meta.getAllValues().get(1).contains("\"revision\":null"),
                meta.getAllValues().get(1));
    }

    // ---- multi-step plan card: threshold consumption + plan-frame emission ----

    private static final String PLAN_KEY = "ai.chat.plan-threshold";
    private static final String PLAN_ARGS_4 =
            "{\"steps\":[\"拉取失败清单\",\"按模块归因\",\"批量生成缺陷草案\",\"汇总回报\"]}";

    @Test
    void planProposalAtOrAboveDefaultThresholdJournalsOnePlanFrameBeforeTheDoneFrame() {
        ChatRunOrchestrator orchestrator = harness("true");
        stubCaseToolOutcome(outcomeWithToolCall("plan.propose", PLAN_ARGS_4));

        List<String> frames = journalFrames(orchestrator, RUN);

        String plan = frames.stream().filter(f -> f.startsWith("plan|")).findFirst()
                .orElseThrow(() -> new AssertionError("plan frame missing: " + frames));
        assertTrue(frames.stream().filter(f -> f.startsWith("plan|")).count() == 1,
                "one proposal journals exactly one card: " + frames);
        // payload names the step count; meta carries the ordered step list the
        // client renders as tickable/reorderable entries
        assertTrue(plan.contains("4"), plan);
        assertTrue(plan.contains("\"index\":1"), plan);
        assertTrue(plan.contains("拉取失败清单"), plan);
        assertTrue(plan.contains("汇总回报"), plan);
        int planIdx = frames.indexOf(plan);
        int doneIdx = -1;
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).startsWith("done|")) {
                doneIdx = i;
                break;
            }
        }
        assertTrue(doneIdx > planIdx, "done must follow the plan card: " + frames);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void planProposalBelowTheHotUpdatedThresholdLandsNoPlanFrame() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PLAN_KEY)).thenReturn("5");
        stubCaseToolOutcome(outcomeWithToolCall("plan.propose", PLAN_ARGS_4));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("plan|")),
                "4 steps under a threshold of 5 stays card-free: " + frames);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void dirtyThresholdValueFallsBackToThreeAndNeverRefusesTheTurn() {
        ChatRunOrchestrator orchestrator = harness("true");
        when(gateway.getSysConfig(PLAN_KEY)).thenReturn("abc");
        stubCaseToolOutcome(outcomeWithToolCall("plan.propose", PLAN_ARGS_4));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().anyMatch(f -> f.startsWith("plan|")),
                "an unparsable key falls back to the default 3, the turn keeps running: "
                        + frames);
        verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void unparsablePlanArgumentsJournalNoHalfShapeCard() {
        ChatRunOrchestrator orchestrator = harness("true");
        stubCaseToolOutcome(outcomeWithToolCall("plan.propose", "not-json"));

        List<String> frames = journalFrames(orchestrator, RUN);

        assertTrue(frames.stream().noneMatch(f -> f.startsWith("plan|")),
                "a hallucinated argument body is honest absence, never a baiting card: " + frames);
        verify(gateway).closeChatMessage(42L, "done");
    }
}
