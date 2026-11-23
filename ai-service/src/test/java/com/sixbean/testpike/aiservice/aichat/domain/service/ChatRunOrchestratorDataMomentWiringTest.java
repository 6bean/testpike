package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingCitation;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingQuality;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.RUN;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.outcomeWithAnswer;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stubDialogDoors;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dialog-side clause of the data-moment stamp (behaviour kernel round-2 item
 * 5): whenever the turn grounded on [C] materials, the system message must
 * instruct the model to label factual conclusions with an as-of moment and
 * to state missing moments honestly instead of inventing a timestamp. The
 * zero-hit posture keeps its legacy bytes (it already refuses fact claims).
 */
class ChatRunOrchestratorDataMomentWiringTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final ReActRunner runner = mock(ReActRunner.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final GroundingService grounding = mock(GroundingService.class);

    private String systemPromptWithGroundedPool() {
        stubDialogDoors(gateway, runner, router, grounding);
        when(grounding.assemble(anyLong(), any(), anyBoolean())).thenReturn(
                new GroundingOutcome(
                        List.of(new GroundingCitation(1, "knowledge", 7L, null,
                                "回滚窗口说明", "字段：阈值=30分钟", 0.5, null, null)),
                        "[C1] 回滚窗口说明: 字段：阈值=30分钟\n【数据时刻】未记录\n",
                        false, GroundingQuality.HIGH, List.of()));
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), any(), any(), anyLong(), 
                any(ReActStreamSink.class))).thenReturn(outcomeWithAnswer("答"));
        ChatRunOrchestrator orchestrator = GoalPlanOrchestratorFixtures.newOrchestrator(
                gateway, runner, router, grounding, null);
        orchestrator.run(RUN);
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner, atLeastOnce()).runStreaming(captor.capture(), anyLong(), any(),
                anyLong(), any(), any(), anyLong(),  any(ReActStreamSink.class));
        return captor.getAllValues().get(0).messages().stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow().content();
    }

    @Test
    void groundedTurnCarriesTheAsOfMomentClause() {
        String system = systemPromptWithGroundedPool();
        assertTrue(system.contains("截至"),
                "fact conclusions must be told to carry an as-of moment: " + system);
        assertTrue(system.contains("不得虚构时间"),
                "the clause must ban invented timestamps: " + system);
    }

    @Test
    void zeroHitTurnKeepsTheLegacyRefusalShape() {
        stubDialogDoors(gateway, runner, router, grounding);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), any(), any(), anyLong(), 
                any(ReActStreamSink.class))).thenReturn(outcomeWithAnswer("答"));
        ChatRunOrchestrator orchestrator = GoalPlanOrchestratorFixtures.newOrchestrator(
                gateway, runner, router, grounding, null);
        orchestrator.run(RUN);
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(runner, atLeastOnce()).runStreaming(captor.capture(), anyLong(), any(),
                anyLong(), any(), any(), anyLong(),  any(ReActStreamSink.class));
        String system = captor.getAllValues().get(0).messages().stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow().content();
        assertTrue(system.contains("直说不知道"), "legacy zero-hit posture unchanged: " + system);
        assertTrue(!system.contains("截至"), "no materials, no as-of instruction: " + system);
    }
}
