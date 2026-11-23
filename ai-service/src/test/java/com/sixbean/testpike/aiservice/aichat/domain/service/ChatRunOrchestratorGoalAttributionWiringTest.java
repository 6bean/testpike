package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GoalAttributionGate;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedger;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.OPEN_STEPS;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.RUN;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.outcomeWithAnswer;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stubDialogDoors;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stubLedgerReads;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stateFramePayloadsOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog wiring of the goal-lifecycle attribution guard (behaviour kernel
 * round-2 item 1, ledger reverse guardrail): after a turn ends with an open
 * ledger, the autonomous continuation now rides on the model's self-reported
 * attribution frame of the LAST answer —
 * <ul>
 * it continues into the new answer (topic-switch residue ban);</li>
 * <li>no frame → fail-safe: the loop does not auto-carry (continuity may
 * be lost, correctness may not be polluted); the ledger itself stays
 * </ul>
 * The guard only exists while its factory-on gate is open — with the switch
 * closed the loop keeps the exact legacy posture (carries without asking
 * the answer for a frame).
 */
class ChatRunOrchestratorGoalAttributionWiringTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore store = new GoalPlanLedgerStore(jdbc);

    private ChatRunOrchestrator harness() {
        stubDialogDoors(gateway, runner, router, grounding);
        return GoalPlanOrchestratorFixtures.newOrchestrator(gateway, runner, router,
                grounding, store);
    }

    /** The initial turn's answer decides whether the loop may carry on. */
    private void initialAnswer(String answer) {
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), any(), any(), anyLong(), 
                any(ReActStreamSink.class))).thenReturn(outcomeWithAnswer(answer));
    }

    @Test
    void continueFrameKeepsTheAutonomousCarry() {
        ChatRunOrchestrator orchestrator = harness();
        initialAnswer("理解为 梳理回归计划｜约束 无\n【目标归属】续旧\n答");
        when(gateway.getSysConfig("ai.chat.goal.max-auto-batches")).thenReturn("1");
        stubLedgerReads(jdbc, List.of(OPEN_STEPS));
        orchestrator.run(RUN);
        verify(runner, times(2)).runStreaming(any(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
    }

    @Test
    void openNewFrameClosesTheLedgerWithTraceAndCarriesNothing() {
        ChatRunOrchestrator orchestrator = harness();
        initialAnswer("理解为 换话题问设备｜约束 无\n【目标归属】开新：先问设备清单\n答");
        stubLedgerReads(jdbc, List.of(OPEN_STEPS), List.of(OPEN_STEPS),
                List.of(GoalPlanLedger.withNewGoalClosure(OPEN_STEPS)));
        orchestrator.run(RUN);
        // No second batch: the answer of a topic-switch turn stays clean.
        verify(runner, times(1)).runStreaming(any(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        // The closure is a real write: skipped steps with the trace note.
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, atLeastOnce()).update(any(String.class), params.capture());
        boolean traced = params.getAllValues().stream().anyMatch(p ->
                String.valueOf(p.getValue("steps")).contains(GoalPlanLedger.NOTE_NEW_GOAL_CLOSURE));
        assertTrue(traced, "开新 closure must land in the stored ledger with its trace note");
        // The user sees an honest sentence about the closed goal.
        assertTrue(stateFramePayloadsOf(gateway).stream()
                        .anyMatch(s -> s.contains("旧目标已显式结束") || s.contains("开新")),
                "closure must be reported to the dialog as a state frame");
    }

    @Test
    void missingFrameIsAFailSafeNoCarryAndLeavesTheLedgerUntouched() {
        ChatRunOrchestrator orchestrator = harness();
        initialAnswer("答：直接回答了新话题");
        stubLedgerReads(jdbc, List.of(OPEN_STEPS), List.of(OPEN_STEPS));
        orchestrator.run(RUN);
        verify(runner, times(1)).runStreaming(any(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
        verify(jdbc, never()).update(any(String.class), any(MapSqlParameterSource.class));
        assertTrue(stateFramePayloadsOf(gateway).stream()
                .anyMatch(s -> s.contains("目标归属") && s.contains("继续")));
    }

    @Test
    void closedGateRestoresTheExactLegacyCarryPosture() {
        when(gateway.getSysConfig(GoalAttributionGate.KEY_ENABLED)).thenReturn("false");
        when(gateway.getSysConfig("ai.chat.goal.max-auto-batches")).thenReturn("1");
        ChatRunOrchestrator orchestrator = harness();
        initialAnswer("答：没有归属行的旧形态回答");
        stubLedgerReads(jdbc, List.of(OPEN_STEPS));
        orchestrator.run(RUN);
        verify(runner, times(2)).runStreaming(any(), eq(3L), any(), eq(7L), isNull(),
                isNull(), anyLong(),  any(ReActStreamSink.class));
    }

    @Test
    void attributionClauseRidesTheSystemHeadWhileTheGateIsOpen() {
        when(gateway.promptTemplateLatestByStatus(GoalAttributionGate.TEMPLATE_NAME,
                GoalAttributionGate.TEMPLATE_DOMAIN, GoalAttributionGate.PUBLISHED_STATUS))
                .thenReturn(null);
        ChatRunOrchestrator orchestrator = harness();
        stubLedgerReads(jdbc, List.of());
        ArgumentCaptor<ChatCompletionRequest> requests =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        orchestrator.run(RUN);
        verify(runner, atLeastOnce()).runStreaming(requests.capture(), anyLong(), any(), anyLong(),
                any(), any(), anyLong(),  any(ReActStreamSink.class));
        assertTrue(requests.getAllValues().get(0).messages().get(0).textContent()
                .contains("【目标归属】"), "head must instruct the self-report frame");
    }

    @Test
    void closedGateInjectsNoClause() {
        when(gateway.getSysConfig(GoalAttributionGate.KEY_ENABLED)).thenReturn("false");
        ChatRunOrchestrator orchestrator = harness();
        stubLedgerReads(jdbc, List.of());
        ArgumentCaptor<ChatCompletionRequest> requests =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        orchestrator.run(RUN);
        verify(runner, atLeastOnce()).runStreaming(requests.capture(), anyLong(), any(), anyLong(),
                any(), any(), anyLong(),  any(ReActStreamSink.class));
        assertTrue(!requests.getAllValues().get(0).messages().get(0).textContent()
                .contains("【目标归属】"));
    }
}
