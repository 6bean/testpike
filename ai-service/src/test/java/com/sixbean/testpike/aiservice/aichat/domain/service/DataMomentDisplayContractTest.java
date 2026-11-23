package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingCitation;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingQuality;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.sixbean.testpike.aiservice.aichat.domain.component.GroundingPoolFixtures.grade;
import static com.sixbean.testpike.aiservice.aichat.domain.component.GroundingPoolFixtures.knowledge;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.RUN;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.outcomeWithAnswer;
import static com.sixbean.testpike.aiservice.aichat.domain.service.GoalPlanOrchestratorFixtures.stubDialogDoors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Display-side contract of the data-moment stamp (behaviour kernel round-2
 * item 5, front half): the pool moment the grounding kernel already computes
 * from ledger timestamps must reach the client as a STRUCTURED value on the
 * existing retrieval state frame, so the answer surface can render the
 * "as-of" mini-label with zero extra calls. Three shapes stay honest:
 * <ul>
 *   <li>timed pool → outcome exposes poolMoment and the retrieval state
 *       frame carries a {@code {"dataMoment":"<iso>"}} meta;</li>
 *   <li>untimed pool → no moment on the outcome, meta stays null (the
 *       model-side sentence already says the moment is unknown; the client
 *       must not invent a clock either);</li>
 *   <li>zero-hit → legacy bytes: no meta ever rides the refusal frame.</li>
 * </ul>
 */
class DataMomentDisplayContractTest {

    private static final OffsetDateTime EARLIER =
            OffsetDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime LATEST =
            OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NOW = LATEST;

    // ---- kernel-side structured exposure -----------------------------------

    @Test
    void timedPoolExposesItsMostRecentMomentOnTheOutcome() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(1L, "旧版说明", "回滚窗口 阈值=20分钟", EARLIER),
                knowledge(2L, "回滚窗口说明", "回滚窗口 阈值=30分钟", LATEST)), NOW);
        assertEquals(LATEST, outcome.poolMoment(),
                "the display side must read the same moment the prompt line names");
    }

    @Test
    void untimedPoolExposesNoMoment() {
        var outcome = grade("回滚窗口阈值是多少", List.of(
                knowledge(1L, "回滚窗口说明", "回滚窗口 阈值=30分钟", null)), NOW);
        assertNull(outcome.poolMoment(),
                "an all-untimed pool has no moment — the client must not fabricate one");
    }

    @Test
    void zeroHitOutcomeCarriesNoMoment() {
        assertNull(GroundingOutcome.none().poolMoment(),
                "the honest zero-hit shape never gains a clock");
    }

    // ---- orchestrator-side frame meta --------------------------------------

    private String retrievalFrameMeta(GroundingOutcome stubbed) {
        CoreGateway gateway = mock(CoreGateway.class);
        ReActRunner runner = mock(ReActRunner.class);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        GroundingService grounding = mock(GroundingService.class);
        stubDialogDoors(gateway, runner, router, grounding);
        when(grounding.assemble(anyLong(), any(), anyBoolean())).thenReturn(stubbed);
        when(runner.runStreaming(any(), anyLong(), any(), anyLong(), any(), any(),
                any(ReActStreamSink.class))).thenReturn(outcomeWithAnswer("答"));
        GoalPlanOrchestratorFixtures.newOrchestrator(gateway, runner, router, grounding, null)
                .run(RUN);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metas = ArgumentCaptor.forClass(String.class);
        verify(gateway, atLeastOnce())
                .appendChatFrame(anyLong(), anyLong(), types.capture(), texts.capture(),
                        metas.capture());
        for (int i = 0; i < texts.getAllValues().size(); i++) {
            if ("state".equals(types.getAllValues().get(i))
                    && texts.getAllValues().get(i).startsWith("检索完成")) {
                return metas.getAllValues().get(i);
            }
        }
        throw new AssertionError("no retrieval state frame was journaled");
    }

    @Test
    void groundedRetrievalFrameCarriesTheMomentMeta() {
        var outcome = new GroundingOutcome(
                List.of(new GroundingCitation(1, "knowledge", 7L, null,
                        "回滚窗口说明", "字段：阈值=30分钟", 0.5, null, null)),
                "[C1] 回滚窗口说明: 字段：阈值=30分钟\n【数据时刻】已标注\n",
                false, GroundingQuality.HIGH, List.of(), LATEST);
        String meta = retrievalFrameMeta(outcome);
        assertTrue(meta != null && meta.contains("\"dataMoment\":\"" + LATEST + "\""),
                "the as-of label's single source is this frame meta, got: " + meta);
    }

    @Test
    void untimedGroundedFrameKeepsMetaNull() {
        var outcome = new GroundingOutcome(
                List.of(new GroundingCitation(1, "knowledge", 7L, null,
                        "回滚窗口说明", "字段：阈值=30分钟", 0.5, null, null)),
                "[C1] 回滚窗口说明: 字段：阈值=30分钟\n【数据时刻】未记录\n",
                false, GroundingQuality.HIGH, List.of(), null);
        assertNull(retrievalFrameMeta(outcome),
                "no moment computed, no meta claimed");
    }

    @Test
    void zeroHitFrameKeepsTheLegacyNullMeta() {
        assertNull(retrievalFrameMeta(GroundingOutcome.none()),
                "the refusal posture keeps its exact old bytes");
    }
}
