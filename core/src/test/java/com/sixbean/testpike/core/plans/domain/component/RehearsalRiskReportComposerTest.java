package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.batch.domain.component.CiGatePolicy;
import com.sixbean.testpike.core.plans.api.dto.RehearsalRiskReportView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * anchors: the rehearsal risk report card assembled on the
 * read-only analysis channel. The composer reads the batch facts from the
 * ledger (never from the model), reuses CiGatePolicy as the single verdict
 * source and the sufficiency service as the single coverage source, and
 * refuses missing or out-of-slice batches by name with zero side effects.
 */
class RehearsalRiskReportComposerTest {

    private JdbcTemplate jdbcTemplate;
    private CiGatePolicy ciGatePolicy;
    private SufficiencyAnalysisService readService;
    private RehearsalRiskReportComposer composer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        ciGatePolicy = mock(CiGatePolicy.class);
        readService = mock(SufficiencyAnalysisService.class);
        composer = new RehearsalRiskReportComposer(jdbcTemplate, ciGatePolicy, readService);
    }

    /** Stub the batch header read (project segment, name, status, source plan). */
    private void givenBatch(long batchId, long projectId, String name, int status, Long planId) {
        when(jdbcTemplate.queryForList(startsWith("SELECT project_id"), eq(batchId)))
                .thenReturn(List.of(Map.of("project_id", projectId, "name", name,
                        "status", status, "plan_id", planId == null ? 0 : planId)));
    }

    private void givenCounts(long total, long passed, long failed, long skipped, long notRun) {
        when(jdbcTemplate.queryForList(startsWith("SELECT count(*)"), eq(512L)))
                .thenReturn(List.of(Map.of("total", total, "passed", passed, "failed", failed,
                        "skipped", skipped, "not_run", notRun)));
    }

    private void givenFailedRows(List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForList(startsWith("SELECT bc.case_id"), eq(512L)))
                .thenReturn(rows);
    }

    private CiVerdictView verdict(String kind) {
        return new CiVerdictView(512L, kind, "演练判定示例", new BigDecimal("0.8200"),
                new BigDecimal("0.9500"), "plan", List.of());
    }

    private CoverageSummaryView coverage() {
        return new CoverageSummaryView(3L, null, null, 40, 31, List.of("REQ-12"), false,
                0, false, -1, null);
    }

    @Test
    void refusesWhenBatchIdIsMissingAndTouchesNoLedger() {
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(3L, null));
        assertTrue(refusal.getMessage().contains("batchId"), refusal.getMessage());
        // Field-proven 2026-10-31: without an explicit code the global handler
        // fell back to 50000, so the missing-id refusal must carry 40001.
        assertEquals(Integer.valueOf(BizErrorCode.VALIDATION_ERROR), refusal.getCode(),
                "the missing-batchId refusal must name a business validation code");
        verifyNoInteractions(jdbcTemplate, ciGatePolicy, readService);
    }

    @Test
    void refusesWhenBatchRowIsAbsentNamingTheIdAndWithoutSideReads() {
        when(jdbcTemplate.queryForList(startsWith("SELECT project_id"), eq(512L)))
                .thenReturn(List.of());

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(3L, 512L));
        assertTrue(refusal.getMessage().contains("512"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("草案确认回执"),
                "refusal must point at the receipt that carries the batch id");
        assertEquals(Integer.valueOf(BizErrorCode.NOT_FOUND), refusal.getCode(),
                "an absent batch id is a not-found refusal, never the 50000 fallback");
        verifyNoInteractions(ciGatePolicy, readService);
    }

    @Test
    void refusesWhenBatchLivesOutsideTheConversationSlice() {
        givenBatch(512L, 9L, "演练·支付回归集", 6, 77L);

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> composer.compose(3L, 512L));
        assertTrue(refusal.getMessage().contains("512"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("当前项目"), refusal.getMessage());
        assertEquals(Integer.valueOf(BizErrorCode.FORBIDDEN), refusal.getCode(),
                "a cross-slice batch id is a forbidden refusal, never the 50000 fallback");
        verifyNoInteractions(ciGatePolicy, readService);
    }

    @Test
    void assemblesTheCardFromTheExistingSingleSourcesWithNoRecomputation() {
        givenBatch(512L, 3L, "演练·支付回归集", 6, 77L);
        givenCounts(10, 7, 2, 1, 0);
        givenFailedRows(List.of(
                Map.of("case_id", 41L, "title", "登录成功", "error_type", "断言失败",
                        "flaky", false, "duration_ms", 1200L),
                Map.of("case_id", 55L, "title", "下单超时", "error_type", "超时",
                        "flaky", true, "duration_ms", 30000L)));
        CiVerdictView gateVerdict = verdict("fail");
        CoverageSummaryView coverageView = coverage();
        when(ciGatePolicy.evaluate(512L, 6, 77L)).thenReturn(gateVerdict);
        when(readService.coverageSummary(3L, null, null)).thenReturn(coverageView);

        RehearsalRiskReportView view = composer.compose(3L, 512L);

        assertEquals(512L, view.batchId());
        assertEquals("演练·支付回归集", view.batchName());
        assertSame(gateVerdict, view.verdict(), "verdict must be passed through, never rebuilt");
        assertSame(coverageView, view.coverage(), "coverage must be the same-source view as-is");
        assertEquals(10, view.totalCases());
        assertEquals(7, view.passed());
        assertEquals(2, view.failed());
        assertEquals(1, view.skipped());
        assertEquals(0, view.notRun());
        assertFalse(view.failedCasesTruncated());
        assertEquals(List.of("登录成功", "下单超时"),
                view.failedCases().stream().map(line -> line.title()).toList());
        assertTrue(view.failedCases().get(1).flaky(), "J1 flaky flag rides the ledger row");
        assertTrue(view.caliberNote().contains("批次徽标"),
                "card must tell the reader which screens share its numbers: " + view.caliberNote());
    }

    @Test
    void capsTheFailedCaseListAtThirtyRowsAndFlagsTruncation() {
        givenBatch(512L, 3L, "演练·大批次", 7, null);
        givenCounts(300, 250, 40, 10, 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            rows.add(Map.of("case_id", (long) i, "title", "用例" + i, "error_type", "断言失败",
                    "flaky", false, "duration_ms", 10L));
        }
        givenFailedRows(rows);
        when(ciGatePolicy.evaluate(512L, 7, null)).thenReturn(verdict("fail"));
        when(readService.coverageSummary(3L, null, null)).thenReturn(coverage());

        RehearsalRiskReportView view = composer.compose(3L, 512L);

        assertEquals(30, view.failedCases().size(), "display-scale cap of the card");
        assertTrue(view.failedCasesTruncated());
    }

    @Test
    void carriesHumanReadableDisplayFieldsSoTheAnswerBodyNeverQuotesRawKeys() {
        // On-screen copy audit: the conversation answer quoted raw wire keys
        // (gateEnabled=false, threshold source=config, passRate, errorType,
        // flaky, status=7 as a colloquial "run finished", coverage -1 mixed
        // with the covered/total fraction). The card must therefore carry
        // display sentences and labels derived from the same single sources.
        givenBatch(512L, 3L, "演练·支付回归集", 7, 77L);
        givenCounts(10, 7, 2, 1, 0);
        givenFailedRows(List.of(
                Map.of("case_id", 41L, "title", "登录成功", "error_type", "2",
                        "flaky", false, "duration_ms", 1200L),
                Map.of("case_id", 55L, "title", "下单超时", "error_type", "断言失败",
                        "flaky", true, "duration_ms", 30000L),
                Map.of("case_id", 66L, "title", "无分类失败", "error_type", "0",
                        "flaky", false, "duration_ms", 5L)));
        when(ciGatePolicy.evaluate(512L, 7, 77L)).thenReturn(verdict("fail"));
        when(readService.coverageSummary(3L, null, null)).thenReturn(coverage());

        RehearsalRiskReportView view = composer.compose(3L, 512L);

        assertEquals("部分失败", view.batchStatusLabel(),
                "status 7 must ride the single-source enum wording, never a colloquial label");
        assertTrue(view.verdictDisplay().contains("判定不通过"), view.verdictDisplay());
        assertTrue(view.verdictDisplay().contains("通过率 82.0%"), view.verdictDisplay());
        assertTrue(view.verdictDisplay().contains("95.0%"), view.verdictDisplay());
        assertTrue(view.verdictDisplay().contains("回归计划设定的准出通过率"),
                "the threshold source must be named in the operator's own terms: "
                        + view.verdictDisplay());
        assertFalse(view.verdictDisplay().contains("plan"),
                "the raw source code must not reach the screen: " + view.verdictDisplay());
        assertTrue(view.coverageDisplay().contains("需求覆盖 31/40"), view.coverageDisplay());
        assertTrue(view.coverageDisplay().contains("契约覆盖准出门禁未启用"), view.coverageDisplay());
        assertFalse(view.coverageDisplay().contains("-1"),
                "the -1 sentinel must never mix into the on-screen fraction caliber: "
                        + view.coverageDisplay());
        assertEquals("定位错误", view.failedCases().get(0).errorTypeLabel(),
                "numeric ledger categories map through the defects single source");
        assertEquals("断言失败", view.failedCases().get(1).errorTypeLabel(),
                "free-text ledger categories keep their recorded wording");
        assertEquals("未分类", view.failedCases().get(2).errorTypeLabel());
        assertEquals("稳定", view.failedCases().get(0).flakyLabel());
        assertEquals("不稳定", view.failedCases().get(1).flakyLabel());
    }

    @Test
    void pendingVerdictAndEnabledGateReadThroughTheSameDisplayFields() {
        givenBatch(512L, 3L, "演练·进行中", 4, null);
        givenCounts(10, 7, 1, 0, 2);
        givenFailedRows(List.of());
        when(ciGatePolicy.evaluate(512L, 4, null)).thenReturn(new CiVerdictView(
                512L, "pending", "批次尚未结束（当前状态：执行中），不做硬判",
                null, null, null, List.of()));
        when(readService.coverageSummary(3L, null, null)).thenReturn(
                new CoverageSummaryView(3L, null, null, 40, 31, List.of(), false,
                        60, true, -1, null));

        RehearsalRiskReportView view = composer.compose(3L, 512L);

        assertEquals("执行中", view.batchStatusLabel());
        assertEquals("批次尚未结束（当前状态：执行中），不做硬判", view.verdictDisplay(),
                "a pending verdict passes the human phrase through, no invented numbers");
        assertTrue(view.coverageDisplay().contains("暂无契约接口"), view.coverageDisplay());

        givenBatch(512L, 3L, "演练·有契约", 6, null);
        when(ciGatePolicy.evaluate(512L, 6, null)).thenReturn(new CiVerdictView(
                512L, "pass", "通过", new BigDecimal("0.9600"), new BigDecimal("0.9500"),
                "config", List.of()));
        when(readService.coverageSummary(3L, null, null)).thenReturn(
                new CoverageSummaryView(3L, null, null, 40, 31, List.of(), false,
                        60, true, 37, null));

        RehearsalRiskReportView measured = composer.compose(3L, 512L);
        assertTrue(measured.verdictDisplay().contains("判定通过"), measured.verdictDisplay());
        assertTrue(measured.verdictDisplay().contains("系统设置的门禁通过率"),
                measured.verdictDisplay());
        assertTrue(measured.coverageDisplay().contains("契约覆盖率 37%"),
                measured.coverageDisplay());
        assertTrue(measured.coverageDisplay().contains("准出阈值 60%"),
                measured.coverageDisplay());
    }

    @Test
    void keepsDeletedCaseRowsWithTheirIdAndNoTitle() {
        givenBatch(512L, 3L, "演练·含已删用例", 6, null);
        givenCounts(5, 3, 2, 0, 0);
        Map<String, Object> removedCaseRow = new java.util.HashMap<>();
        removedCaseRow.put("case_id", 77L);
        removedCaseRow.put("title", null);
        removedCaseRow.put("error_type", null);
        removedCaseRow.put("flaky", false);
        removedCaseRow.put("duration_ms", null);
        givenFailedRows(List.of(removedCaseRow));
        when(ciGatePolicy.evaluate(512L, 6, null)).thenReturn(verdict("fail"));
        when(readService.coverageSummary(3L, null, null)).thenReturn(coverage());

        RehearsalRiskReportView view = composer.compose(3L, 512L);

        assertNull(view.failedCases().get(0).title(), "LEFT JOIN keeps removed cases");
        assertEquals(77L, view.failedCases().get(0).caseId());
    }
}
