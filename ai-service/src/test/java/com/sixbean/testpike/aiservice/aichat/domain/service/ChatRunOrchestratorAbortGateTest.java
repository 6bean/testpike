package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.OPEN_STEPS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestrator gates of the chat-abort contract : when the
 * user's abort has sealed the turn in core, the run plane must add nothing
 * more — no draft cards, no plan/clarify frames, no goal auto batch, and
 * above all no done seal that would overwrite the honest aborted status.
 * The abort is observed through the ledger sink's single read door at the
 * costs at most the in-flight provider call.
 * <p>L2 posture identical to the goal-plan wiring tests: the provider chain
 * never runs (the loop itself is covered by ChatAbortCheckpointTest); what
 * is proven here is the orchestrator's write discipline after an abort.
 */
class ChatRunOrchestratorAbortGateTest {

    private static final AiChatRunMessage RUN = GoalPlanOrchestratorFixtures.RUN;

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore store = new GoalPlanLedgerStore(jdbc);

    private ChatRunOrchestrator harness() {
        GoalPlanOrchestratorFixtures.stubDialogDoors(gateway, runner, router, grounding);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), isNull(), isNull(),
                anyLong(), any())).thenReturn(GoalPlanOrchestratorFixtures.outcome());
        when(jdbc.queryForList(any(String.class), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn(List.of(OPEN_STEPS));
        return GoalPlanOrchestratorFixtures.newOrchestrator(gateway, runner, router,
                grounding, store);
    }

    @Test
    void abortedRunWritesNothingAfterTheSeal() {
        when(gateway.chatMessageStatus(42L)).thenReturn("aborted");

        harness().run(RUN);

        verify(runner, times(1)).runStreaming(any(), anyLong(), any(), anyLong(),
                isNull(), isNull(), anyLong(), any());
        verify(gateway, never()).closeChatMessage(eq(42L), any());
        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("done"),
                any(), any());
    }

    @Test
    void abortBetweenBatchesStopsTheNextBatchAndTheSeal() {
        // poll #1 = run-end gate (not yet aborted), poll #2 = batch-1 head
        // (batch 1 legitimately runs), poll #3 = post-batch read — the
        // abort lands here: no batch 2, no draft emission, no done seal.
        when(gateway.chatMessageStatus(42L)).thenReturn("streaming", "streaming", "aborted");

        harness().run(RUN);

        verify(runner, times(2)).runStreaming(any(), anyLong(), any(), anyLong(),
                isNull(), isNull(), anyLong(), any());
        verify(gateway, never()).closeChatMessage(eq(42L), any());
        verify(gateway, never()).appendChatFrame(anyLong(), anyLong(), eq("done"),
                any(), any());
    }

    @Test
    void unreachableStatusDoorKeepsTheLegacyTrajectory() {
        when(gateway.chatMessageStatus(42L)).thenThrow(new IllegalStateException("core down"));

        harness().run(RUN);

        // Fail-soft: a dead read door must not kill a healthy turn — the
        // legacy happy path still lands its done seal.
        verify(gateway).closeChatMessage(eq(42L), eq("done"));
    }
}
