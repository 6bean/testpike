package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportExportFile;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>R37 fixture contract: the list/pagination/sort pins no longer rely on a
 * blanket DELETE+TRUNCATE to make the whole table run-exclusive. Instead each
 * run writes its snapshots into a random far-past period window (derived from
 * the run token, re-rolled while the window touches a month boundary) and
 * anchors every list query with that window, so the exact same counts and
 * orders are asserted without seeing anybody else's rows. Teardown deletes
 * only the ids created above the per-test watermark plus the tracked fixture
 * projects.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class ReportCenterQueryExportTest {

    @Autowired
    private ReportCenterService reportCenterService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Run-exclusive far-past anchors: the weekly window plus its natural month. */
    private LocalDateTime weekStart;
    private LocalDateTime weekEnd;
    private LocalDateTime monthStart;
    private LocalDateTime monthEnd;

    /** Run-owned fixture ledger (R37): tracked project ids + tp_report watermark. */
    private final List<Long> projectIds = new ArrayList<>();
    private long reportIdFloor;
    private String runToken;

    @BeforeEach
    void markWatermarkAndAnchorRunWindow() {
        runToken = SharedDbFixtureSweep.newToken();
        reportIdFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_report");
        // Anchor on a random natural month 12-24 years back: shipped rows
        // never land there, so the run's period window shows exactly its own
        // snapshots (R37 re-anchor for the old table-wipe). The weekly legs sit
        // in the month's 8th-14th (the Monday of the week containing day 14),
        // so every seeded period — monthly monthStart, weekStart and
        // weekStart-7d — stays inside the single [monthStart, monthEnd)
        // filter window.
        int monthsBack = 150 + ThreadLocalRandom.current().nextInt(150);
        Timestamp m = jdbcTemplate.queryForObject(
                "SELECT date_trunc('month', now() - (? * interval '1 month'))",
                Timestamp.class, monthsBack);
        monthStart = m.toLocalDateTime();
        monthEnd = monthStart.plusMonths(1);
        Timestamp w = jdbcTemplate.queryForObject(
                "SELECT date_trunc('week', ?::timestamp + interval '14 days')",
                Timestamp.class, monthStart.toString());
        weekStart = w.toLocalDateTime();
        weekEnd = weekStart.plusDays(7);
    }

    @AfterEach
    void sweepRunOwnedRows() {
        SharedDbFixtureSweep.reportsAndProjects(jdbcTemplate, reportIdFloor, projectIds);
    }

    
    private int auditExportCount(long reportId) {
        Integer c = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log"
                        + " WHERE object_type='report' AND action='export' AND object_id=?",
                Integer.class, String.valueOf(reportId));
        return c == null ? 0 : c;
    }

    /** Generate one project-scope weekly snapshot inside this run's far-past window. */
    private ReportView seedWeekly(long pid) {
        return reportCenterService.generate(1L, new ReportGenerateInput(1, 1, pid,
                weekStart.toString(), weekEnd.toString()));
    }

    /** Generate one global-scope monthly snapshot inside the run month. */
    private ReportView seedGlobalMonthly() {
        return reportCenterService.generate(1L, new ReportGenerateInput(2, 2, null,
                monthStart.toString(), monthEnd.toString()));
    }


    @Test
    void listFiltersByTypeScopePeriodAndPaginates() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC1");
        seedWeekly(pid);
        seedGlobalMonthly();

        PageResult<ReportListItem> all = reportCenterService.list(new ReportQuery(1, 20,
                null, null, null, monthStart.toString(), monthEnd.toString()));
        assertEquals(2, all.total(), "运行窗口内全量应 2 行（R37 窗口口径=本 run 全部快照）");

        PageResult<ReportListItem> weeklyOnly = reportCenterService.list(new ReportQuery(1, 20,
                1, null, null, monthStart.toString(), monthEnd.toString()));
        assertEquals(1, weeklyOnly.total(), "reportType=1 筛选应只剩周报");
        assertEquals(1, weeklyOnly.list().get(0).reportType());
        assertEquals(pid, weeklyOnly.list().get(0).scopeId(), "列表行须携带范围维度供前端展示");

        PageResult<ReportListItem> globalOnly = reportCenterService.list(new ReportQuery(1, 20,
                null, 2, null, monthStart.toString(), monthEnd.toString()));
        assertEquals(1, globalOnly.total(), "scopeType=2 筛选只剩全局");
        assertEquals(0L, globalOnly.list().get(0).scopeId(), "全局维行 scope_id=0（V53 裁定②读侧）");

        PageResult<ReportListItem> inPeriod = reportCenterService.list(new ReportQuery(1, 20,
                null, null, null, weekStart.toString(), weekEnd.toString()));
        assertEquals(1, inPeriod.total(), "期间筛选应命中周一起始的周报");
        PageResult<ReportListItem> outPeriod = reportCenterService.list(new ReportQuery(1, 20,
                null, null, null, weekEnd.toString(), weekEnd.plusWeeks(4).toString()));
        assertEquals(0, outPeriod.total(), "窗口外不得串入");

        PageResult<ReportListItem> p1 = reportCenterService.list(new ReportQuery(1, 1,
                null, null, null, monthStart.toString(), monthEnd.toString()));
        PageResult<ReportListItem> p2 = reportCenterService.list(new ReportQuery(2, 1,
                null, null, null, monthStart.toString(), monthEnd.toString()));
        assertEquals(1, p1.list().size());
        assertEquals(2, p1.total());
        assertEquals(1, p2.list().size());
        assertNotNull(p2.list().get(0).id());
        assertTrue(!p1.list().get(0).id().equals(p2.list().get(0).id()), "翻页须返回不同行");
    }

    // ── page 19: server-side sort over the tp_report ledger ──

    /** Order witness: every neighbour pair satisfies the comparator (ties allowed). */
    private static void assertOrdered(java.util.List<ReportListItem> rows,
                                      java.util.Comparator<ReportListItem> by) {
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(by.compare(rows.get(i - 1), rows.get(i)) <= 0,
                    "sort violated at index " + i + ": " + rows);
        }
    }

    @Test
    void listSortsThroughWhitelistWithIdTieBreakAndRefusesOffListKeys() {
        long pidA = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC19A");
        long pidB = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC19B");
        // Sort pins read the ledger through the run month window: all four
        // seeded snapshots (two weeklies inside the second week, two monthlies
        // at the month start) have their period_start inside it.
        String from = monthStart.toString();
        String to = monthEnd.toString();
        reportCenterService.generate(1L, new ReportGenerateInput(1, 1, pidA,
                weekStart.toString(), weekEnd.toString()));
        reportCenterService.generate(1L, new ReportGenerateInput(1, 1, pidB,
                weekStart.minusWeeks(1).toString(), weekEnd.minusWeeks(1).toString()));
        reportCenterService.generate(1L, new ReportGenerateInput(2, 1, pidB,
                monthStart.toString(), monthEnd.toString()));
        reportCenterService.generate(1L, new ReportGenerateInput(2, 2, null,
                monthStart.toString(), monthEnd.toString()));

        // Zero-drift pin 1: the legacy 7-arg entry delegates with the null pair,
        // record equality proves no silent default changed inside the DTO.
        assertEquals(new ReportQuery(1, 20, null, null, null, null, null),
                new ReportQuery(1, 20, null, null, null, null, null, null, null));

        // Zero-drift pin 2: no sortBy keeps the historical period_start DESC, id DESC.
        PageResult<ReportListItem> legacy = reportCenterService.list(
                new ReportQuery(1, 20, null, null, null, from, to));
        assertEquals(4, legacy.total(), "four snapshots seeded; run window [" + from + ", " + to
                + ") weekStart=" + weekStart + " monthStart=" + monthStart + " ledger="
                + jdbcTemplate.queryForObject("SELECT coalesce(string_agg(id || ':t' || report_type"
                        + " || ':s' || scope_type || ':' || scope_id || '@' || period_start::text, ' | '),"
                        + " '(empty)') FROM tp_report"
                        + " WHERE period_start >= ?::timestamp AND period_start < ?::timestamp",
                        String.class, from, to) + "]");
        assertOrdered(legacy.list(), java.util.Comparator.comparing(ReportListItem::periodStart)
                .thenComparing(ReportListItem::id).reversed());

        // generated_at asc crosses the page boundary with the id DESC tie-break:
        // page1+page2 joined must satisfy the comparator end to end.
        PageResult<ReportListItem> g1 = reportCenterService.list(
                new ReportQuery(1, 2, null, null, null, from, to, "generated_at", "asc"));
        PageResult<ReportListItem> g2 = reportCenterService.list(
                new ReportQuery(2, 2, null, null, null, from, to, "generated_at", "asc"));
        java.util.List<ReportListItem> joined = new java.util.ArrayList<>(g1.list());
        joined.addAll(g2.list());
        assertEquals(4, joined.size(), "pagination window stays gap-free under sort");
        assertOrdered(joined, java.util.Comparator.comparing(ReportListItem::generatedAt)
                .thenComparing(ReportListItem::id, java.util.Comparator.reverseOrder()));

        // report_type asc groups the two weeklies before the two monthlies.
        PageResult<ReportListItem> typed = reportCenterService.list(
                new ReportQuery(1, 20, null, null, null, from, to, "report_type", "asc"));
        assertEquals(1, typed.list().get(0).reportType());
        assertEquals(1, typed.list().get(1).reportType());
        assertEquals(2, typed.list().get(2).reportType());
        assertEquals(2, typed.list().get(3).reportType());

        // scope_id desc leads with project B (two rows), then A; desc mirrors asc.
        PageResult<ReportListItem> scopeDesc = reportCenterService.list(
                new ReportQuery(1, 20, null, null, null, from, to, "scope_id", "desc"));
        assertEquals(2, scopeDesc.list().stream().filter(r -> pidB == r.scopeId()).count());
        assertEquals(2, scopeDesc.list().subList(0, 2).stream()
                .filter(r -> pidB == r.scopeId()).count(), "desc window leads with the larger scope");

        // Sort composes with a filter: monthly segment ordered by period_start desc.
        PageResult<ReportListItem> monthlySorted = reportCenterService.list(
                new ReportQuery(1, 20, 2, null, null, from, to, "period_start", "desc"));
        assertEquals(2, monthlySorted.total());
        assertOrdered(monthlySorted.list(), java.util.Comparator.comparing(ReportListItem::periodStart)
                .thenComparing(ReportListItem::id, java.util.Comparator.reverseOrder()));

        // Fail-closed refusals (before any read): an off-list real column and an
        // injection-shaped key both get a 400 naming the allowed set.
        ServiceException offList = assertThrows(ServiceException.class,
                () -> reportCenterService.list(new ReportQuery(1, 20, null, null, null, from, to,
                        "payload_json", "asc")));
        assertTrue(offList.getMessage().contains("payload_json"), "报错点名非法列");
        assertTrue(offList.getMessage().contains("generated_at"), "报错含允许清单");
        ServiceException injection = assertThrows(ServiceException.class,
                () -> reportCenterService.list(new ReportQuery(1, 20, null, null, null, from, to,
                        "id; DROP TABLE tp_report", "asc")));
        assertTrue(injection.getMessage().contains("report_type"), "注入形态同样拒且点名白名单");

        // Refusals left nothing behind: the window still answers the default order.
        assertEquals(4, reportCenterService.list(
                new ReportQuery(1, 20, null, null, null, from, to)).total(), "拒后数据完好");
    }


    @Test
    void detailReturnsPayloadVerbatimAndMissingRejected() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC2");
        ReportView generated = seedWeekly(pid);

        ReportView detail = reportCenterService.detail(generated.id());
        assertEquals(generated.id(), detail.id());
        assertNotNull(detail.payloadJson());
        assertArrayEquals(generated.payloadJson().getBytes(StandardCharsets.UTF_8),
                detail.payloadJson().getBytes(StandardCharsets.UTF_8),
                "详情 payload 必须原文回读（口径版本注记不失真，禁服务端二次组装）");
        assertTrue(detail.payloadJson().contains("\"version\""), "payload 应含口径版本号");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> reportCenterService.detail(999_999_999L));
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());
    }


    @Test
    void exportCsvContainsCaliberRowsAndSafeFileName() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC3");
        ReportView view = seedWeekly(pid);

        ReportExportFile file = reportCenterService.export(1L, view.id(), "csv", null);
        assertEquals("text/csv;charset=UTF-8", file.contentType());
        assertTrue(file.fileName().endsWith(".csv"), "文件名: " + file.fileName());
        String text = new String(file.content(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM（Excel 直开中文不乱码）");
        assertTrue(text.contains("通过率"), "CSV 应含口径中文名: " + text.substring(0, Math.min(200, text.length())));
        assertTrue(text.contains("v1.0"), "CSV 应内嵌口径版本（TC-O10-02 导出链路）");
        assertTrue(text.contains("周报"), "CSV 元信息行含报表类型");
    }


    @Test
    void exportRejectsUnknownFormatWithLegalValues() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC4");
        ReportView view = seedWeekly(pid);

        int auditBefore = auditExportCount(view.id());
        ServiceException bad = assertThrows(ServiceException.class,
                () -> reportCenterService.export(1L, view.id(), "xls", null));
        assertTrue(bad.getMessage().contains("csv") && bad.getMessage().contains("pdf"),
                "非法 format 报错须含合法值: " + bad.getMessage());
        assertEquals(auditBefore, auditExportCount(view.id()),
                "值域拒绝不得先写审计再抛（成功导出才留痕；append-only 表按 object_id 计数，坑13）");
    }


    @Test
    void exportOverMaxRowsRejectedWithHumanHint() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC5");
        ReportView view = seedWeekly(pid);
        try {
            configCacheHolder.put("report.export-max-rows", "1");
            ServiceException over = assertThrows(ServiceException.class,
                    () -> reportCenterService.export(1L, view.id(), "csv", null));
            assertTrue(over.getMessage().contains("上限"), "报错人话含上限: " + over.getMessage());
            assertTrue(over.getMessage().contains("收窄"), "报错须指引缩范围: " + over.getMessage());
        } finally {
            configCacheHolder.put("report.export-max-rows", "100000");
        }
        assertNotNull(reportCenterService.export(1L, view.id(), "csv", null).content());
    }


    @Test
    void exportPdfBothThemesAndRejectsBadTheme() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RC6");
        ReportView view = seedWeekly(pid);

        ReportExportFile light = reportCenterService.export(1L, view.id(), "pdf", null);
        assertEquals("application/pdf", light.contentType());
        assertTrue(light.fileName().endsWith(".pdf"));
        assertPdfHeader(light.content(), "默认主题");

        ReportExportFile dark = reportCenterService.export(1L, view.id(), "pdf", "dark");
        assertPdfHeader(dark.content(), "dark 主题");

        ServiceException badTheme = assertThrows(ServiceException.class,
                () -> reportCenterService.export(1L, view.id(), "pdf", "neon"));
        assertTrue(badTheme.getMessage().contains("light") && badTheme.getMessage().contains("dark"),
                "非法 theme 报错含合法值: " + badTheme.getMessage());
    }

    private static void assertPdfHeader(byte[] pdf, String label) {
        assertTrue(pdf.length > 1000, label + " PDF 字节过小（渲染未生效？）");
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"),
                label + " 输出非 PDF");
    }
}
