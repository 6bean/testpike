package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Single-source fixture for the goal-plan orchestrator tests (R40): the run
 * messages, the stored step lists and the frame-door readers are shared by
 * ChatRunOrchestratorGoalPlanWiringTest and ChatRunOrchestratorGoalCostWiringTest
 * instead of being pasted twice — a fixture edited in one copy only would
 * silently change one suite's ground truth.
 */
final class GoalPlanOrchestratorFixtures {

    /** Session 9 / assistant message 42 / project 3 / user 7 — dialog turn. */
    static final AiChatRunMessage RUN =
            new AiChatRunMessage(9L, 42L, 3L, 7L, "帮我梳理回归计划", List.of(), null, List.of());

    /** Two open steps as the ledger stores them (evidence empty, pending). */
    static final String OPEN_STEPS =
            "[{\"title\":\"收集需求\",\"state\":\"pending\",\"evidence\":\"\"},"
                    + "{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"}]";

    /** One anchored done step — a closed ledger the auto loop can finish on. */
    static final String CLOSED_STEPS =
            "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"tool-call-7\"}]";

    private GoalPlanOrchestratorFixtures() {
    }

    /**
 * Plain-text answer outcome with no tool calls (the loop's idle shape).
 * The answer carries the round-2 attribution frame (goal lifecycle):
 * compliant-model default answer must carry it for the legacy
 * auto-continue expectations to stay meaningful.
 */
    static ReActOutcome outcome() {
        return outcomeWithAnswer("【目标归属】续旧\n答");
    }

    /** Answer outcome carrying an arbitrary final-answer text. */
    static ReActOutcome outcomeWithAnswer(String answer) {
        return new ReActOutcome(new LlmChatResult(answer, "p", "m", 10, 5, 5L, null),
                List.of(), 0, 0, 0, false, null);
    }

    /**
     * Orchestrator assembly shared by every goal-plan wiring test (R40):
     * resolver seams over the same gateway mock, no experience/citation
     * collaborators, and the ledger store attached or absent (legacy 12-arg
     * shape).
     */
    static ChatRunOrchestrator newOrchestrator(
            com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway gateway,
            com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner runner,
            ProjectLlmRouter router, GroundingService grounding,
            GoalPlanLedgerStore store) {
        if (store != null) {
            return new ChatRunOrchestrator(gateway, runner, router, grounding,
                    new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                    null, null, null, null,
                    new AiCtxConfigResolver(gateway, new AiCtxProperties()), null, store);
        }
        return new ChatRunOrchestrator(gateway, runner, router, grounding,
                new RefObjectResolver(gateway), new FailureDiagnosisResolver(gateway),
                null, null, null, null,
                new AiCtxConfigResolver(gateway, new AiCtxProperties()), null);
    }

    /**
     * Chained stored-ledger reads through the store's real FIND_SQL seam
     * (single source with ChatRunOrchestratorGoalPlanWiringTest): each ledger
     * check — the loop read and an upsert's internal read alike — consumes
     * one queued value, repeats take the last.
     */
    @SafeVarargs
    static void stubLedgerReads(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                                 java.util.List<String>... sequence) {
        when(jdbc.queryForList(any(String.class),
                any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class),
                eq(String.class))).thenReturn(sequence[0],
                java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
    }

    /**
     * The shared stub set every goal-plan wiring test needs (R40 single
     * source: exec door on, a routed endpoint, empty grounding, one canned
     * outcome per streaming run). Tests needing chained per-call answers
     * override the runner stub afterwards.
     */
    static void stubDialogDoors(com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway gateway,
                                com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner runner,
                                ProjectLlmRouter router,
                                com.sixbean.testpike.aiservice.aichat.domain.service.GroundingService grounding) {
        when(gateway.getSysConfig("ai.chat.exec-enabled")).thenReturn("true");
        when(router.route(anyLong())).thenReturn(new com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig(
                "p", "m-test", "k", "http://x",
                com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig.SOURCE_DEFAULT));
        when(grounding.assemble(anyLong(), any(), anyBoolean()))
                .thenReturn(com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome.none());
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(), anyLong(), any(com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink.class)))
                .thenReturn(outcome());
    }

    /**
     * Every "state"-frame payload the core gateway mock received for the
     * standard session/message identity, in call order. A non-mock stub of
     * the gateway (e.g. a fake) simply yields no invocations and answers
     * empty — the reader never throws on those shapes.
     */
    static List<String> stateFramePayloadsOf(com.sixbean.testpike.aiservice.gen
            .infrastructure.remote.CoreGateway gateway) {
        List<String> states = new ArrayList<>();
        try {
            ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(gateway, atLeastOnce()).appendChatFrame(eq(9L), eq(42L), type.capture(),
                    payload.capture(), isNull());
            for (int i = 0; i < type.getAllValues().size(); i++) {
                if ("state".equals(type.getAllValues().get(i))) {
                    states.add(payload.getAllValues().get(i));
                }
            }
        } catch (org.mockito.exceptions.base.MockitoException noMockedCall) {
            return states;
        }
        return states;
    }
}
