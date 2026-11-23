package com.sixbean.testpike.core.analysis.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.plans.api.dto.RehearsalDraftView;
import com.sixbean.testpike.core.plans.domain.component.RehearsalDraftComposer;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * anchors: the rehearsal-draft function joins the read-only
 * analysis door. The door must whitelist its two id filters, hand the
 * server-carried segment to the composer (never a model-fillable project key),
 * and refuse the wrong filter shapes by name.
 */
class AnalysisFunctionDispatcherRehearsalTest {

    private SufficiencyAnalysisService readService;
    private RehearsalDraftComposer composer;
    private ToolAccessGate accessGate;
    private AnalysisFunctionDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        readService = mock(SufficiencyAnalysisService.class);
        composer = mock(RehearsalDraftComposer.class);
        accessGate = mock(ToolAccessGate.class);
        dispatcher = new AnalysisFunctionDispatcher(readService, composer,
                mock(com.sixbean.testpike.core.plans.domain.component
                        .RehearsalRiskReportComposer.class),
                accessGate);
    }

    @Test
    void rehearsalDraftIsCallableAndCarriesTheServerSliceToTheComposer() throws Exception {
        RehearsalDraftView view = new RehearsalDraftView("SUITE", 77L, "支付回归集", 3,
                "演练·支付回归集·2026-10-31", List.of(9L), null,
                Map.of("name", "x", "envIds", List.of(9L)), "演练草案已装配：确认这张卡后会走计划创建链");
        when(composer.compose(3L, 77L, null)).thenReturn(view);

        Object out = dispatcher.run(3L, 1L, "rehearsalDraft",
                json.readTree("{\"suiteId\":77}"));

        assertEquals(view, out);
        verify(composer).compose(3L, 77L, null);
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    @Test
    void unknownFilterKeyOnRehearsalDraftIsRefusedWithTheVocabularyNamed() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> dispatcher.run(3L, 1L, "rehearsalDraft",
                        json.createObjectNode().put("projectId", 99)));
        assertTrue(refusal.getMessage().contains("projectId"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("suiteId") && refusal.getMessage().contains("planId"),
                "refusal must name the accepted filters");
    }

    @Test
    void nonNumericIdFilterIsRefusedInPlainWords() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> dispatcher.run(3L, 1L, "rehearsalDraft",
                        json.createObjectNode().put("suiteId", "not-a-number")));
        assertTrue(refusal.getMessage().contains("suiteId"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("整数"), refusal.getMessage());
    }

    @Test
    void missingVisibilityOnTheSliceNeverReachesTheComposer() {
        org.mockito.Mockito.doThrow(new ServiceException("无权查看该项目",
                        com.sixbean.testpike.common.exception.BizErrorCode.FORBIDDEN))
                .when(accessGate).requireProjectVisible(3L, 1L);

        assertThrows(ServiceException.class,
                () -> dispatcher.run(3L, 1L, "rehearsalDraft", json.createObjectNode().put("suiteId", 77)));
        verify(composer, never()).compose(anyLong(), any(), any());
    }
}
