package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanReconciler;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.CLOSED_STEPS;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.OPEN_STEPS;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.RUN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog wiring of the goal-plan ledger (AX batch , autonomous
 * continuation ): the orchestrator hangs the finish door on the sink only
 * when the storage seam is present; when a turn ends with the ledger still
 * open the run carries on with the NEXT batch on its own — the user is never
 * asked to type a resume word first (the manual trigger stays as a fallback
 * done, a failed round, or the safety cap K8 key
 * {@code ai.chat.goal.max-auto-batches} (garbage or out-of-range values fall
 * back to the factory default 3). The legacy 12-argument shape (no store)
 * must keep the exact old behaviour: no door, no auto batch, no ledger block.
 * <p>L2 posture: the store rides through its real SQL seam backed by a
 * mocked jdbc door, so the wiring proves the actual read paths, not a fake.
 */
class ChatRunOrchestratorGoalPlanWiringTest {

    private static final AiChatRunMessage RESUME =
            new AiChatRunMessage(9L, 42L, 3L, 7L, "继续", List.of(), null, List.of());

    private static final String KEY_AUTO_BATCHES = "ai.chat.goal.max-auto-batches";


    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore store = new GoalPlanLedgerStore(jdbc);

    /** One stored-ledger read shape for the store's FIND_SQL seam. */
    private void storedLedger(String stepsJson) {
        when(jdbc.queryForList(any(String.class), any(MapSqlParameterSource.class),
                eq(String.class)))
                .thenReturn(stepsJson == null ? List.of() : List.of(stepsJson));
    }

    /** Chained read shapes: each ledger check consumes one queued value. */
    @SafeVarargs
    private void storedLedgerReads(List<String>... sequence) {
        when(jdbc.queryForList(any(String.class), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(sequence[0],
                java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
    }

    private ChatRunOrchestrator harnessWithStore() {
        return harness(true);
    }

    private ChatRunOrchestrator harnessWithoutStore() {
        return harness(false);
    }

    private ChatRunOrchestrator harness(boolean withStore) {
        GoalPlanOrchestratorFixtures.stubDialogDoors(gateway, runner, router, grounding);
        return GoalPlanOrchestratorFixtures.newOrchestrator(gateway, runner, router,
                grounding, withStore ? store : null);
    }

    private static ReActOutcome outcome() {
        return GoalPlanOrchestratorFixtures.outcome();
    }

    private ReActStreamSink capturedSink(ChatRunOrchestrator orchestrator,
                                          AiChatRunMessage message) {
        orchestrator.run(message);
        ArgumentCaptor<ReActStreamSink> captor = ArgumentCaptor.forClass(ReActStreamSink.class);
        verify(runner, org.mockito.Mockito.atLeastOnce()).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(),  captor.capture());
        return captor.getValue();
    }

    private String systemHead(ChatRunOrchestrator orchestrator, AiChatRunMessage message) {
        return allSystemHeads(orchestrator, message).get(0);
    }

    /** Every system head the turn sent, in call order (initial run first). */
    private List<String> allSystemHeads(ChatRunOrchestrator orchestrator,
                                        AiChatRunMessage message) {
        orchestrator.run(message);
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner, org.mockito.Mockito.atLeastOnce()).runStreaming(captor.capture(),
                eq(3L), any(), eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        return captor.getAllValues().stream()
                .map(request -> request.messages().get(0).textContent()).toList();
    }

    private List<String> stateFramePayloads() {
        return GoalPlanOrchestratorFixtures.stateFramePayloadsOf(gateway);
    }

    @Test
    void storedLedgerSeamHangsTheGoalFinishDoorOnTheSink() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedger(null);

        ReActStreamSink sink = capturedSink(orchestrator, RUN);

        assertInstanceOf(GoalPlanReconciler.class, sink.finishReconciler(),
                "with the ledger store wired the plain-text finish must consult it");
    }

    @Test
    void legacyShapeWithoutStoreKeepsTheExactOldFinishDoorlessSink() {
        ChatRunOrchestrator orchestrator = harnessWithoutStore();

        ReActStreamSink sink = capturedSink(orchestrator, RUN);

        assertNull(sink.finishReconciler(),
                "no store = no door: the loop finishes exactly like before the slice");
    }

    @Test
    void openLedgerAutoContinuesTheNextBatchWithoutAskingTheUser() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS));

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(2)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.startsWith("目标计划还有")
                        && s.contains("收集需求") && s.contains("生成用例")
                        && s.contains("自动接续第 1 批")),
                "the auto batch announces the open steps it picks up: " + states);
        assertTrue(states.stream().noneMatch(s -> s.contains("回复「继续」")),
                "the manual-resume footer is retired: " + states);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(gateway);
        order.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("state"),
                org.mockito.ArgumentMatchers.contains("自动接续第 1 批"), isNull());
        order.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("done"),
                org.mockito.ArgumentMatchers.anyString(), isNull());
        order.verify(gateway).closeChatMessage(42L, "done");
    }

    @Test
    void autoBatchRequestCarriesTheLedgerBlockIntoTheSystemHead() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS));

        List<String> heads = allSystemHeads(orchestrator, RUN);

        assertFalse(heads.get(0).contains("【目标账本】"),
                "the initial plain question never carries the ledger");
        assertTrue(heads.size() >= 2, "one auto batch must have run");
        assertTrue(heads.get(1).contains("【目标账本】") && heads.get(1).contains("生成用例"),
                "the continuation request carries the open ledger like a resume turn");
    }

    @Test
    void openLedgerBeyondTheSafetyCapStopsAndReportsHonestly() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        when(gateway.getSysConfig(KEY_AUTO_BATCHES)).thenReturn("2");
        storedLedger(OPEN_STEPS);

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(3)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.startsWith("目标计划还有")
                        && s.contains(KEY_AUTO_BATCHES + "=2") && s.contains("发送「继续」")
                        && s.contains("收集需求")),
                "the cap stop names the key, the remaining steps and the manual door: " + states);
        assertTrue(states.stream().noneMatch(s -> s.contains("回复「继续」")),
                "no footer ever asks the user to type the trigger first: " + states);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(gateway);
        order.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("state"),
                org.mockito.ArgumentMatchers.contains(KEY_AUTO_BATCHES), isNull());
        order.verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("done"),
                org.mockito.ArgumentMatchers.anyString(), isNull());
    }

    @Test
    void garbageOrOutOfRangeCapFallsBackToTheFactoryDefaultThree() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        when(gateway.getSysConfig(KEY_AUTO_BATCHES)).thenReturn("99");
        storedLedger(OPEN_STEPS);

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(4)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        assertTrue(stateFramePayloads().stream()
                        .anyMatch(s -> s.contains(KEY_AUTO_BATCHES + "=3")),
                "the honest stop reports the value actually honoured: " + stateFramePayloads());
    }

    @Test
    void closedLedgerFromTheStartRunsNoAutoBatch() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedger(CLOSED_STEPS);

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(1)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        assertTrue(stateFramePayloads().stream().noneMatch(s -> s.contains("继续")),
                "a fully closed ledger never re-enters the dialog surface");
    }

    @Test
    void closedLedgerAfterAnAutoBatchClosesTheTailHonestly() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS));

        orchestrator.run(RUN);

        assertTrue(stateFramePayloads().stream()
                        .anyMatch(s -> s.startsWith("目标计划还有 0 步未完成") && s.contains("自动接续")),
                "the finish sentence flips the card door to zero open steps");
    }

    @Test
    void resumeTurnAssemblesTheStoredLedgerIntoTheSystemHead() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedger(OPEN_STEPS);

        String head = systemHead(orchestrator, RESUME);

        assertTrue(head.contains("【目标账本】"), "the continue turn carries the ledger: " + head);
        assertTrue(head.contains("生成用例"), "and its open titles: " + head);
    }

    @Test
    void plainPromptNeverCarriesTheLedgerBlockIntoTheSystemHead() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedger(OPEN_STEPS);

        String head = systemHead(orchestrator, RUN);

        assertFalse(head.contains("【目标账本】"),
                "only the resume command and an auto batch read the ledger into the head");
    }

    @Test
    void resumeTurnWithoutStoreDegradesToThePlainTurn() {
        ChatRunOrchestrator orchestrator = harnessWithoutStore();

        String head = systemHead(orchestrator, RESUME);

        assertFalse(head.contains("【目标账本】"),
                "no storage seam = the resume word is just a question again");
    }

    /**
 * The live FC shape (L4 red family A1/A2/A3/A8): the model writes the
 * self-report line on the first, tool-calling round and the sealed final
 * round is only a short closer. The guard must read the whole streamed
 * frame within this run unlocks the carry; the closer-only reading
 * wrongly answered FRAME_MISSING and stopped the auto batch.
 */
    @Test
    void fcTurnReadsTheSelfReportFromTheWholeStreamedAnswer() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS));
        when(runner.runStreaming(any(), eq(3L), any(), eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenAnswer(invocation -> {
            ReActStreamSink streamSink = invocation.getArgument(7);
            streamSink.onDelta("按既定目标推进。\n【目标归属】续旧\n先取清单。");
            streamSink.onToolStart("case.list");
            streamSink.onToolDone("case.list", true, "{}");
            return GoalPlanOrchestratorFixtures.outcomeWithAnswer("以上即本轮进展。");
        });

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(2)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.contains("自动接续第 1 批")),
                "the carry decision reads the whole streamed answer, not only the closer: "
                        + states);
        assertTrue(states.stream().noneMatch(s -> s.contains("未自报目标归属")),
                "no false missing-frame stop when the self-report rode an earlier round: "
                        + states);
    }

    @Test
    void autoBatchKeepsRunningWhileTheLedgerShrinksStepByStep() {
        ChatRunOrchestrator orchestrator = harnessWithStore();
        when(gateway.getSysConfig(KEY_AUTO_BATCHES)).thenReturn("3");
        storedLedgerReads(List.of(OPEN_STEPS), List.of(OPEN_STEPS), List.of(CLOSED_STEPS));

        orchestrator.run(RUN);

        verify(runner, org.mockito.Mockito.times(3)).runStreaming(any(), eq(3L), any(),
                eq(7L), isNull(), isNull(), anyLong(), any(ReActStreamSink.class));
        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.contains("自动接续第 2 批")),
                "the second batch carries its own sequence number: " + states);
        assertEquals(0, states.stream().filter(s -> s.contains("回复「继续」")).count(),
                "the user is never asked to type the trigger first");
    }
}
