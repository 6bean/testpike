package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring of the progress-cost line into the goal-plan ledger sentences
 * (AX batch item "progress cost transparency", §6 consumption
 * visibility): every open-tail frame the auto-batch loop emits — the batch
 * pickup, the safety-cap stop and the zero-open close — must carry the
 * session's consumed tokens, and the ledger door
 * ({@link CoreGateway#sessionTotalTokens}) must be read ONCE per turn like
 * the budget gate already does, never re-polled inside the batch loop.
 * <p>L2 posture mirrors ChatRunOrchestratorGoalPlanWiringTest: the store
 * rides its real SQL seam on a mocked jdbc door; only the frame sentences
 * and the read count are pinned here.
 */
class ChatRunOrchestratorGoalCostWiringTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore store = new GoalPlanLedgerStore(jdbc);

    private ChatRunOrchestrator harness() {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn("true");
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "p", "m-test", "k", "http://x", LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(GroundingOutcome.none());
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(ReActStreamSink.class))).thenReturn(GoalPlanOrchestratorFixtures.outcome());
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, null, null, null, new AiCtxConfigResolver(gateway, new AiCtxProperties()),
                null, store);
    }

    /** Chained ledger reads: each open-tail check consumes one queued value. */
    @SafeVarargs
    private void storedLedgerReads(List<String>... sequence) {
        when(jdbc.queryForList(any(String.class), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(sequence[0],
                java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
    }

    private List<String> stateFramePayloads() {
        return GoalPlanOrchestratorFixtures.stateFramePayloadsOf(gateway);
    }

    @Test
    void everyOpenTailFrameCarriesTheConsumedTokensCostTail() {
        when(gateway.sessionTotalTokens(9L)).thenReturn(12345L);
        // Two batches: first pickup announces the open ledger, the second
        // finds it closed and lands the zero-open close sentence.
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS), List.of(CLOSED_STEPS));

        harness().run(RUN);

        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.startsWith("目标计划还有 2 步未完成")
                        && s.contains("自动接续第 1 批") && s.contains("本会话已消耗 12345 tokens")),
                "the batch-pickup frame must carry the cost tail: " + states);
        assertTrue(states.stream().anyMatch(s -> s.startsWith("目标计划还有 0 步未完成")
                        && s.contains("本会话已消耗 12345 tokens")),
                "the zero-open close frame must carry the cost tail: " + states);
    }

    @Test
    void safetyCapStopSentenceCarriesTheCostTail() {
        when(gateway.sessionTotalTokens(9L)).thenReturn(999L);
        when(gateway.getSysConfig("ai.chat.goal.max-auto-batches")).thenReturn("1");
        storedLedgerReads(List.of(OPEN_STEPS), List.of(OPEN_STEPS));

        harness().run(RUN);

        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.contains("自主续跑已达安全上限")
                        && s.contains("本会话已消耗 999 tokens")),
                "the cap stop must tell the user what the run already cost: " + states);
    }

    @Test
    void consumptionDoorIsReadOncePerTurnNotPerBatch() {
        when(gateway.sessionTotalTokens(9L)).thenReturn(12345L);
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS), List.of(CLOSED_STEPS));

        harness().run(RUN);

        verify(gateway, times(1)).sessionTotalTokens(9L);
    }

    @Test
    void unreadableLedgerRendersTheHonestCannotReadTail() {
        when(gateway.sessionTotalTokens(9L)).thenThrow(new IllegalStateException("door down"));
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS), List.of(CLOSED_STEPS));

        harness().run(RUN);

        List<String> states = stateFramePayloads();
        assertTrue(states.stream().anyMatch(s -> s.contains("本会话消耗量本次未能读取")),
                "an unreadable ledger must say so instead of fabricating 0: " + states);
    }

    /** Sanity: the pinned fold prefix survives the append (never rewrite it). */
    @Test
    void costTailNeverDisturbsThePinnedOpenTailPrefix() {
        when(gateway.sessionTotalTokens(9L)).thenReturn(7L);
        storedLedgerReads(List.of(OPEN_STEPS), List.of(CLOSED_STEPS), List.of(CLOSED_STEPS));

        harness().run(RUN);

        List<String> tails = stateFramePayloads().stream()
                .filter(s -> s.startsWith("目标计划还有")).toList();
        assertFalse(tails.isEmpty(), "the auto-batch loop must have emitted open tails");
        // may only ride behind that anchor, never rewrite or truncate it.
        assertTrue(tails.stream().allMatch(s -> s.matches("^目标计划还有 \\d+ 步未完成（.*）.*$")),
                "every open-tail keeps the reducer's pinned anchor: " + tails);
        assertTrue(tails.stream().allMatch(s -> s.endsWith("已消耗 7 tokens。")),
                "every open-tail of this turn carries the cost tail: " + tails);
    }

}
