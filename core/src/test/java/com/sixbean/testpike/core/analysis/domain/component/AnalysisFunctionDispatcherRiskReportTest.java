package com.sixbean.testpike.core.analysis.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.plans.api.dto.CaseRiskLine;
import com.sixbean.testpike.core.plans.api.dto.RehearsalRiskReportView;
import com.sixbean.testpike.core.plans.domain.component.RehearsalDraftComposer;
import com.sixbean.testpike.core.plans.domain.component.RehearsalRiskReportComposer;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * anchors: the risk-report function joins the read-only analysis
 * door. The door whitelists its single batchId filter, hands the
 * server-carried project segment plus the ledger-read batch id to the composer,
 * and never lets the batch number be replaced by a model-fillable project key.
 */
class AnalysisFunctionDispatcherRiskReportTest {

    private SufficiencyAnalysisService readService;
    private RehearsalDraftComposer draftComposer;
    private RehearsalRiskReportComposer riskComposer;
    private ToolAccessGate accessGate;
    private AnalysisFunctionDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        readService = mock(SufficiencyAnalysisService.class);
        draftComposer = mock(RehearsalDraftComposer.class);
        riskComposer = mock(RehearsalRiskReportComposer.class);
        accessGate = mock(ToolAccessGate.class);
        dispatcher = new AnalysisFunctionDispatcher(readService, draftComposer,
                riskComposer, accessGate);
    }

    private RehearsalRiskReportView sampleView() {
        return new RehearsalRiskReportView(512L, "演练·支付回归集", 6, "已完成",
                new CiVerdictView(512L, "pass", "ok", new BigDecimal("0.9800"),
                        new BigDecimal("0.9500"), "plan", List.of()),
                "判定通过：通过率 98.0%，不低于准出阈值 95.0%（阈值来源：回归计划设定的准出通过率）",
                10, 9, 0, 1, 0,
                List.of(new CaseRiskLine(41L, "登录成功", null, "未分类", false, "稳定", 1200L)), false,
                new CoverageSummaryView(3L, null, null, 40, 31, List.of(), false,
                        0, false, -1, null),
                "需求覆盖 31/40；契约覆盖准出门禁未启用。",
                "通过率与门禁判定和批次徽标同一单源");
    }

    @Test
    void riskReportIsInTheCallableVocabulary() {
        assertTrue(dispatcher.callableFunctions().contains("rehearsalRiskReport"),
                "slice 3 must extend the door vocabulary: " + dispatcher.callableFunctions());
        assertEquals(Set.of("coverageSummary", "assertionDensity", "rehearsalDraft",
                        "rehearsalRiskReport"),
                dispatcher.callableFunctions());
    }

    @Test
    void riskReportRoutesTheServerSliceAndBatchIdToTheComposer() throws Exception {
        RehearsalRiskReportView view = sampleView();
        when(riskComposer.compose(3L, 512L)).thenReturn(view);

        Object out = dispatcher.run(3L, 1L, "rehearsalRiskReport",
                json.readTree("{\"batchId\":512}"));

        assertSame(view, out);
        verify(riskComposer).compose(3L, 512L);
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    @Test
    void riskReportWithoutBatchIdReachesTheComposerForItsPlainRefusal() throws Exception {
        when(riskComposer.compose(3L, null)).thenThrow(new ServiceException(
                "取风险报告卡需要批次编号 batchId"));

        assertThrows(ServiceException.class,
                () -> dispatcher.run(3L, 1L, "rehearsalRiskReport", json.createObjectNode()));
        verify(riskComposer).compose(3L, null);
    }

    @Test
    void riskReportRefusesUnknownFilterKeysNamingTheAcceptedOne() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> dispatcher.run(3L, 1L, "rehearsalRiskReport",
                        json.createObjectNode().put("suiteId", 7)));
        assertTrue(refusal.getMessage().contains("suiteId"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("batchId"),
                "refusal must name the accepted filter: " + refusal.getMessage());
    }

    @Test
    void riskReportTextBatchIdIsAcceptedAsTheLedgerKey() throws Exception {
        RehearsalRiskReportView view = sampleView();
        when(riskComposer.compose(3L, 512L)).thenReturn(view);

        Object out = dispatcher.run(3L, 1L, "rehearsalRiskReport",
                json.readTree("{\"batchId\":\"512\"}"));

        assertSame(view, out);
    }
}
