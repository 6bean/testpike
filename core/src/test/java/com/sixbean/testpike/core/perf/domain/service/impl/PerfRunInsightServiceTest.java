package com.sixbean.testpike.core.perf.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;
import com.sixbean.testpike.core.perf.domain.component.PerfAiSwitchGate;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportService;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfInsightClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * Core orchestration leg for the AI run-insight read-out (B-11 :
 * the switch gate must refuse before any data leg or model hop, the facts
 * block must be assembled ONLY from the existing single-source read faces
 * (ciVerdict judge + report metric card + error-class TopN — the red line
 * forbids any recomputation or second judgment head here), a run the judge
 * still answers pending on is explained with zero model tokens, and the
 * ai-service reply travels back untranslated (format refusals are the ai
 * leg's own three-shape contract — core never re-judges the answer).
 */
class PerfRunInsightServiceTest {

    /** ConfigService stub backed by an in-memory row map (PerfAiSwitchGateTest precedent). */
    private static ConfigService mapConfig(Map<String, String> rows) {
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.getValue(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String fallback = invocation.getArgument(1);
                    return rows.getOrDefault(key, fallback);
                });
        return configService;
    }

    private static PerfReportService reportPassing(Map<String, Object> card) {
        PerfReportService report = Mockito.mock(PerfReportService.class);
        Map<String, Object> verdict = new HashMap<>();
        verdict.put("verdict", "pass");
        verdict.put("verdictPhrase", "P95 512ms ≤ 800ms，错误率 0.4% ≤ 1%，吞吐达标，判定通过");
        verdict.put("breachedGates", List.of());
        Mockito.when(report.ciVerdict(anyLong(), anyLong())).thenReturn(verdict);
        Map<String, Object> build = new HashMap<>();
        build.put("card", card);
        build.put("caliberFootnote", "分位为合并桶口径，错误率为加权口径");
        Mockito.when(report.build(anyLong(), anyLong())).thenReturn(build);
        return report;
    }

    private static Map<String, Object> card() {
        Map<String, Object> card = new HashMap<>();
        card.put("sampledSeconds", 300);
        card.put("avgRps", 99.6);
        card.put("maxRps", 112.4);
        card.put("peakSecondP95Ms", 512.0);
        card.put("avgErrorRate", 0.004);
        return card;
    }

    private static PerfRunInsightServiceImpl service(Map<String, String> configRows,
                                                    PerfReportService report,
                                                    PerfMetricService metric,
                                                    AiServicePerfInsightClient client) {
        return new PerfRunInsightServiceImpl(
                new PerfAiSwitchGate(mapConfig(configRows)), report, metric, client);
    }

    @Test
    void closedSwitchRefusesBeforeAnyDataLegOrModelHop() {
        PerfReportService report = Mockito.mock(PerfReportService.class);
        PerfMetricService metric = Mockito.mock(PerfMetricService.class);
        AiServicePerfInsightClient client = Mockito.mock(AiServicePerfInsightClient.class);
        PerfRunInsightServiceImpl svc = service(new HashMap<>(), report, metric, client);

        ServiceException e = assertThrows(ServiceException.class, () -> svc.insight(11L, 42L));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("ai.perf.insight"),
                "refusal must name the exact key an operator flips: " + e.getMessage());
        Mockito.verifyNoInteractions(report, metric, client);
    }

    @Test
    void pendingRunIsAnsweredHonestlyWithZeroModelTokens() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.insight", "true");
        PerfReportService report = Mockito.mock(PerfReportService.class);
        Map<String, Object> verdict = new HashMap<>();
        verdict.put("verdict", "pending");
        verdict.put("verdictPhrase", "压测尚未结束（当前状态：运行中），请等待运行进入终态后再取结论");
        Mockito.when(report.ciVerdict(11L, 42L)).thenReturn(verdict);
        PerfMetricService metric = Mockito.mock(PerfMetricService.class);
        AiServicePerfInsightClient client = Mockito.mock(AiServicePerfInsightClient.class);
        PerfRunInsightServiceImpl svc = service(rows, report, metric, client);

        Map<String, Object> out = svc.insight(11L, 42L);
        assertEquals("pending", out.get("verdict"));
        assertEquals(verdict.get("verdictPhrase"), out.get("verdictPhrase"));
        assertNull(out.get("insight"), "a run with nothing to explain never gets a fabricated read-out");
        Mockito.verifyNoInteractions(client);
        // the pending answer never pays for a second report assembly either
        Mockito.verify(report, Mockito.never()).build(anyLong(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminalFactsRideSingleSourcesIntoExactlyOneModelHop() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.insight", "true");
        PerfReportService report = reportPassing(card());
        PerfMetricService metric = Mockito.mock(PerfMetricService.class);
        Mockito.when(metric.errorClassTopN(11L, 42L)).thenReturn(List.of(
                new PerfErrorClassCount("timeout", 7L),
                new PerfErrorClassCount("http5xx", 2L)));
        AiServicePerfInsightClient client = Mockito.mock(AiServicePerfInsightClient.class);
        Mockito.when(client.explain(any())).thenReturn(
                new HashMap<>(Map.of("insight", "错误集中在超时，瓶颈指向下游响应。")));
        PerfRunInsightServiceImpl svc = service(rows, report, metric, client);

        Map<String, Object> out = svc.insight(11L, 42L);
        assertEquals("错误集中在超时，瓶颈指向下游响应。", out.get("insight"));
        assertEquals("pass", out.get("verdict"));
        assertEquals(42L, out.get("runId"));

        ArgumentCaptor<PerfRunInsightRequest> captor =
                ArgumentCaptor.forClass(PerfRunInsightRequest.class);
        Mockito.verify(client, Mockito.times(1)).explain(captor.capture());
        PerfRunInsightRequest sent = captor.getValue();
        assertEquals(11L, sent.projectId());
        // : the run anchor must ride through to the ai leg — the process
        // event tail on the other side keys job_ref/run_token on exactly this id.
        assertEquals(Long.valueOf(42L), sent.runId());
        String facts = sent.factsBlock();
        // every number in the facts block is copied verbatim from a single source
        assertTrue(facts.contains("判定通过"), "SLA conclusion phrase must ride the facts: " + facts);
        assertTrue(facts.contains("99.6") && facts.contains("112.4")
                        && facts.contains("512") && facts.contains("300"),
                "metric-card numbers must be copied as-is: " + facts);
        assertTrue(facts.contains("timeout=7") && facts.contains("http5xx=2"),
                "error-class TopN must ride the facts in its own order: " + facts);
        assertTrue(facts.contains("分位为合并桶口径"), "caliber footnote must travel too: " + facts);
        assertFalse(facts.isEmpty());
    }

    @Test
    void absentErrorClassesStayAnHonestAbsenceNeverAZeroWall() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.insight", "true");
        PerfReportService report = reportPassing(card());
        PerfMetricService metric = Mockito.mock(PerfMetricService.class);
        Mockito.when(metric.errorClassTopN(11L, 42L)).thenReturn(List.of());
        AiServicePerfInsightClient client = Mockito.mock(AiServicePerfInsightClient.class);
        Mockito.when(client.explain(any())).thenReturn(new HashMap<>(Map.of("insight", "无错误，整体健康。")));
        PerfRunInsightServiceImpl svc = service(rows, report, metric, client);

        Map<String, Object> out = svc.insight(11L, 42L);
        assertEquals("无错误，整体健康。", out.get("insight"));
        ArgumentCaptor<PerfRunInsightRequest> captor =
                ArgumentCaptor.forClass(PerfRunInsightRequest.class);
        Mockito.verify(client).explain(captor.capture());
        assertTrue(captor.getValue().factsBlock().contains("未记录到错误分类"),
                "an empty TopN is stated as an honest absence, never as per-class zero rows");
    }

    @Test
    void aiLegReplyTravelsBackUnjudged() {
        // The ai leg owns the three-shape format refusal (AI_FORMAT_ERROR). Core must
        // NOT re-judge the reply shape here — whatever {insight} comes back rides through.
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.insight", "true");
        PerfReportService report = reportPassing(card());
        PerfMetricService metric = Mockito.mock(PerfMetricService.class);
        Mockito.when(metric.errorClassTopN(11L, 42L)).thenReturn(List.of());
        AiServicePerfInsightClient client = Mockito.mock(AiServicePerfInsightClient.class);
        Mockito.when(client.explain(any())).thenReturn(new HashMap<>(Map.of("insight", "ok")));
        PerfRunInsightServiceImpl svc = service(rows, report, metric, client);

        Map<String, Object> out = svc.insight(11L, 42L);
        assertEquals("ok", out.get("insight"));
    }
}
