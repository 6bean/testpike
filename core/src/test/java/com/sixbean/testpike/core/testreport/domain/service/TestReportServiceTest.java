package com.sixbean.testpike.core.testreport.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.testreport.api.dto.ReportDetailView;
import com.sixbean.testpike.core.testreport.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.testreport.api.dto.ReportQuery;
import com.sixbean.testpike.core.testreport.api.dto.ReportRecordInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-report service suite (summary reports; docs 17 report generation and
 * export, docs 18 permission cases run on the admin side). Runs against the
 * real shared dev database, no mocks.
 * <p>Covers: generate-by-plan aggregation (scope, results, defect
 * distribution, env versions, pass rate, conclusion, persisted report_json);
 * generate-by-time-range (intersecting plans + in-window defects/batches);
 * list/detail; xlsx/pdf export magic-number checks; validation errors —
 * missing plan 404, cross-project 400, missing range params 400, bad export
 * format 400.
 *
 * <p>Shared-database discipline (R37): no blanket TRUNCATE. Project codes live
 * in the fixed TRt* family with a per-run token, a crashed round's leftovers
 * are family-swept before the round opens, and teardown deletes only this
 * suite's tracked projects with their descendants. tp_test_report carries
 * project_id but no FK into tp_project, so it rides the extra-project-tables
 * channel; tp_plan_case keys on tp_exec_plan without an FK, which is why the
 * plans-family join purge runs first. All count assertions are already
 * project- or report-scoped, so no id watermark is needed. The old wipe also
 * cleared tp_user/tp_user_role — factory baseline rows this suite never
 * creates, so they are now left alone entirely.
 */
@SpringBootTest(classes = CoreApplication.class)
class TestReportServiceTest {

    @Autowired
    private TestReportService reportService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** tp_test_report carries project_id but no FK into tp_project. */
    private static final List<String> REPORT_EXTRA_TABLES = List.of("tp_test_report");

    /** Fixture projects created by this run (swept child-first at teardown). */
    private final List<Long> projectIds = new ArrayList<>();

    /** Per-run code token: the global unique-code rule can never collide. */
    private String token;

    @BeforeEach
    void openFixture() {
        token = Long.toHexString(ThreadLocalRandom.current().nextLong(0x10000000L, 0xFFFFFFF0L));
        // Crash-residue guard: a killed round leaves TRt*-coded projects whose
        // codes the global uniqueness rule (40904) would reject on the next
        // create. The family (absent from the shipped baseline) is swept
        // plan-child-first before the round opens.
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, REPORT_EXTRA_TABLES, familyProjectIds());
    }

    @AfterEach
    void sweepFixtureRows() {
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, REPORT_EXTRA_TABLES, projectIds);
        projectIds.clear();
    }

    private List<Long> familyProjectIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'TRt%'", Long.class);
    }

    /** This run's code family; the suffix keeps multi-project tests distinct. */
    private String fam(String suffix) {
        return "TRt" + token + suffix;
    }

    private long createProject(String code) {
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private long createEnv(long pid, String name, int type) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, type,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 3, 1, null, List.of()));
        return info.id();
    }

    
    private long insertPlan(long pid, String name, String version, String start, String end) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_plan (project_id, name, target_version, status, plan_start, plan_end, created_by)
                VALUES (?, ?, ?, 4, ?, ?, 1) RETURNING id
                """, Long.class, pid, name, version, start, end);
    }

    private void insertPlanCase(long planId, long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_plan_case (plan_id, case_id, assignee_id, status) VALUES (?, ?, 1, ?)
                """, planId, caseId, status);
    }

    private void insertDefect(long pid, Long planId, long caseId, int severity, int status, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO tp_defect (project_id, case_id, plan_id, title, severity, status, reporter_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?::timestamp)
                """, pid, caseId, planId, "缺陷T", severity, status, createdAt);
    }

    private void insertBatch(long pid, long envId, Long planId, String version, String finishedAt) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, env_version_snapshot,
                                           plan_id, finished_at, created_at)
                VALUES (?, ?, ?, ?, 6, ?, ?, ?::timestamp, now())
                """, pid, envId, "BN", "批次", version, planId, finishedAt);
    }


    @Test
    void test_generate_by_plan_aggregation_and_export() {
        long pid = createProject(fam("A"));
        long env1 = createEnv(pid, "测试环境A", 2);
        long env2 = createEnv(pid, "预发环境B", 3);
        long c1 = createCase(pid, "登录"), c2 = createCase(pid, "支付"), c3 = createCase(pid, "订单");
        long c4 = createCase(pid, "优惠券");
        long planId = insertPlan(pid, "V2.0 全量回归", "v2.0.0", "2026-09-01", "2026-09-10");
        insertPlanCase(planId, c1, 3);
        insertPlanCase(planId, c2, 3);
        insertPlanCase(planId, c3, 4);
        insertPlanCase(planId, c4, 1);
        insertDefect(pid, planId, c3, 1, 1, "2026-09-03 10:00:00");
        insertDefect(pid, planId, c3, 2, 2, "2026-09-04 10:00:00");
        insertDefect(pid, planId, c2, 3, 5, "2026-09-05 10:00:00");
        insertBatch(pid, env1, planId, "v2.0.1", "2026-09-06 12:00:00");
        insertBatch(pid, env2, planId, "v2.0.2", "2026-09-07 12:00:00");

        ReportRecordInfo record = reportService.generate(1L, new ReportGenerateInput(pid, 1, planId, null, null, null));
        assertNotNull(record);
        assertEquals(planId, record.planId());
        assertTrue(record.title().contains("V2.0 全量回归"), "缺省标题含计划名");
        long reportId = record.id();

        ReportDetailView detail = reportService.get(reportId);
        assertEquals("V2.0 全量回归", detail.scope().planName());
        assertEquals("v2.0.0", detail.scope().targetVersion());
        assertEquals(4, detail.scope().caseTotal());
        assertEquals(4, detail.results().total());
        assertEquals(2, detail.results().passed());
        assertEquals(1, detail.results().failed());
        assertEquals(1, detail.results().notRun());
        assertEquals(66.7, detail.results().passRate(), 0.05, "通过率=2/3=66.7%");
        assertEquals(3, detail.defectDist().total());
        assertEquals(2, detail.defectDist().openCount(), "P0 新建 + P1 处理中未关闭");
        assertEquals(2, detail.envVersions().size(), "2 环境批次聚合");
        assertFalse(detail.conclusion().pass(), "通过率 66.7% < 95% → 不达标");
        assertTrue(detail.conclusion().summary().contains("66.7%"), "结论含通过率");

        PageResult<ReportRecordInfo> page = reportService.list(new ReportQuery(pid, null, 1, 10));
        assertEquals(1, page.total());
        PageResult<ReportRecordInfo> hit = reportService.list(new ReportQuery(pid, "V2.0", 1, 10));
        assertEquals(1, hit.total());
        PageResult<ReportRecordInfo> miss = reportService.list(new ReportQuery(pid, "不存在的标题", 1, 10));
        assertEquals(0, miss.total());

        byte[] xlsx = reportService.export(reportId, "xlsx");
        assertTrue(xlsx.length > 100, "xlsx 非空");
        assertArrayEquals(new byte[]{0x50, 0x4B, 0x03, 0x04},
                new byte[]{xlsx[0], xlsx[1], xlsx[2], xlsx[3]}, "xlsx=Zip 魔数");
        byte[] pdf = reportService.export(reportId, "pdf");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII), "pdf 魔数 %PDF");

        assertThrows(ServiceException.class, () -> reportService.export(reportId, "docx"));

        long otherPid = createProject(fam("X"));
        PageResult<ReportRecordInfo> other = reportService.list(new ReportQuery(otherPid, null, 1, 10));
        assertEquals(0, other.total());
    }


    @Test
    void test_generate_by_time_range() {
        long pid = createProject(fam("B"));
        long env = createEnv(pid, "测试环境A", 2);
        long c1 = createCase(pid, "登录"), c2 = createCase(pid, "支付");
        long inPlan = insertPlan(pid, "窗内计划", "v1.0.0", "2026-09-01", "2026-09-10");
        long outPlan = insertPlan(pid, "窗外计划", "v0.9.0", "2026-07-01", "2026-07-31");
        insertPlanCase(inPlan, c1, 3);
        insertPlanCase(inPlan, c2, 4);
        insertPlanCase(outPlan, c2, 3);
        insertDefect(pid, inPlan, c1, 2, 1, "2026-09-05 10:00:00");
        insertDefect(pid, inPlan, c1, 4, 5, "2026-08-01 10:00:00");
        insertBatch(pid, env, inPlan, "v1.0.1", "2026-09-06 12:00:00");
        insertBatch(pid, env, outPlan, "v0.9.1", "2026-07-20 12:00:00");

        ReportRecordInfo record = reportService.generate(1L,
                new ReportGenerateInput(pid, 2, null, "2026-09-01", "2026-09-10", "九月第一周周报"));
        ReportDetailView detail = reportService.get(record.id());
        assertEquals("九月第一周周报", detail.title());
        assertEquals(2, detail.scope().caseTotal(), "仅窗内计划 2 条用例");
        assertEquals(1, detail.scope().planCount(), "窗内 1 个计划");
        assertEquals(1, detail.results().passed());
        assertEquals(1, detail.results().failed());
        assertEquals(1, detail.defectDist().total(), "仅窗内缺陷");
        assertEquals(1, detail.envVersions().size(), "仅窗内批次");
        assertNull(detail.scope().planName(), "时间范围无计划名");
        assertNull(detail.planId(), "时间范围报告 plan_id 为 null");
    }


    @Test
    void test_validation_errors() {
        long pid = createProject(fam("C"));
        long otherPid = createProject(fam("D"));
        long otherPlan = insertPlan(otherPid, "他人计划", "v1", "2026-09-01", "2026-09-10");

        ServiceException notFound = assertThrows(ServiceException.class,
                () -> reportService.generate(1L, new ReportGenerateInput(pid, 1, 999999L, null, null, null)));
        assertEquals(BizErrorCode.NOT_FOUND, notFound.getCode(), "计划不存在 404");

        ServiceException cross = assertThrows(ServiceException.class,
                () -> reportService.generate(1L, new ReportGenerateInput(pid, 1, otherPlan, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, cross.getCode(), "跨项目 400");

        ServiceException noRange = assertThrows(ServiceException.class,
                () -> reportService.generate(1L, new ReportGenerateInput(pid, 2, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noRange.getCode(), "缺时间范围 400");

        ServiceException badType = assertThrows(ServiceException.class,
                () -> reportService.generate(1L, new ReportGenerateInput(pid, 9, null, "2026-09-01", "2026-09-10", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badType.getCode(), "非法 rangeType 400");

        assertThrows(ServiceException.class, () -> reportService.get(999999L));
    }

    @Test
    void test_generate_passes_when_rate_ok_and_no_open_defects() {
        long pid = createProject(fam("E"));
        long c1 = createCase(pid, "登录");
        long planId = insertPlan(pid, "达标计划", "v3.0.0", "2026-09-01", "2026-09-10");
        insertPlanCase(planId, c1, 3);
        insertDefect(pid, planId, c1, 3, 5, "2026-09-05 10:00:00");

        ReportDetailView detail = reportService.get(
                reportService.generate(1L, new ReportGenerateInput(pid, 1, planId, null, null, null)).id());
        assertEquals(100.0, detail.results().passRate(), 0.05);
        assertTrue(detail.conclusion().pass(), "通过率 100% 且无未关闭缺陷 → 达标");
    }

    // ── page 20: server-side sort over the tp_test_report ledger ──

    /** Order witness: every neighbour pair satisfies the comparator (ties allowed). */
    private static void assertOrdered(java.util.List<ReportRecordInfo> rows,
                                      java.util.Comparator<ReportRecordInfo> by) {
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(by.compare(rows.get(i - 1), rows.get(i)) <= 0,
                    "sort violated at index " + i + ": " + rows);
        }
    }

    @Test
    void test_list_sorts_through_whitelist_with_id_tiebreak_and_refuses_off_list_keys() {
        long pid = createProject(fam("SA"));
        long other = createProject(fam("SB"));
        long c1 = createCase(pid, "登录");
        long planId = insertPlan(pid, "排序计划", "v9.0.0", "2026-09-01", "2026-09-10");
        insertPlanCase(planId, c1, 3);
        // Four rows in one project through the real generate chain: three
        // plan-range rows with ASCII-pinned distinct titles plus one time-range
        // row sharing the middle title (title tie + tie-break witness).
        reportService.generate(1L, new ReportGenerateInput(pid, 1, planId, null, null, "AAA 报告"));
        long tieFirst = reportService.generate(1L,
                new ReportGenerateInput(pid, 1, planId, null, null, "MMM 报告")).id();
        reportService.generate(1L, new ReportGenerateInput(pid, 1, planId, null, null, "ZZZ 报告"));
        long tieSecond = reportService.generate(1L,
                new ReportGenerateInput(pid, 2, null, "2026-09-01", "2026-09-10", "MMM 报告")).id();
        // Foreign-project row: the sorted window must stay the H1 project segment.
        reportService.generate(1L,
                new ReportGenerateInput(other, 2, null, "2026-09-01", "2026-09-10", "AAA 别项目"));

        // Zero-drift pin 1: the legacy 4-arg entry delegates with the null pair,
        // record equality proves no silent default changed inside the DTO.
        assertEquals(new ReportQuery(pid, null, 1, 10), new ReportQuery(pid, null, 1, 10, null, null));

        // Zero-drift pin 2: no sortBy keeps the historical id DESC default order.
        PageResult<ReportRecordInfo> legacy = reportService.list(new ReportQuery(pid, null, 1, 10));
        assertEquals(4, legacy.total(), "four reports seeded in the project slice");
        assertOrdered(legacy.list(), java.util.Comparator.comparing(ReportRecordInfo::id).reversed());

        // title asc: AAA < MMM(tie pair) < ZZZ; the tie resolves by id DESC
        // (deterministic paging requires the tie-break).
        PageResult<ReportRecordInfo> byTitle = reportService.list(
                new ReportQuery(pid, null, 1, 10, "title", "asc"));
        assertEquals(List.of("AAA 报告", "MMM 报告", "MMM 报告", "ZZZ 报告"),
                byTitle.list().stream().map(ReportRecordInfo::title).toList());
        assertEquals(tieSecond, byTitle.list().get(1).id(), "title tie breaks by id DESC (newer first)");
        assertEquals(tieFirst, byTitle.list().get(2).id(), "title tie tail holds the older id");

        // range_type asc crosses the page boundary gap-free: page1+page2 joined
        // satisfies comparator (three type-1 rows before the single type-2 row).
        PageResult<ReportRecordInfo> t1 = reportService.list(
                new ReportQuery(pid, null, 1, 2, "range_type", "asc"));
        PageResult<ReportRecordInfo> t2 = reportService.list(
                new ReportQuery(pid, null, 2, 2, "range_type", "asc"));
        java.util.List<ReportRecordInfo> joined = new java.util.ArrayList<>(t1.list());
        joined.addAll(t2.list());
        assertEquals(4, joined.size(), "pagination window stays gap-free under sort");
        assertOrdered(joined, java.util.Comparator.comparing(ReportRecordInfo::rangeType)
                .thenComparing(ReportRecordInfo::id, java.util.Comparator.reverseOrder()));

        // created_at asc: rows born in the same second fall back to id DESC —
        // the string timestamp alone cannot decide, the tie-break must.
        PageResult<ReportRecordInfo> byTime = reportService.list(
                new ReportQuery(pid, null, 1, 10, "created_at", "asc"));
        assertOrdered(byTime.list(), java.util.Comparator.comparing(ReportRecordInfo::createdAt)
                .thenComparing(ReportRecordInfo::id, java.util.Comparator.reverseOrder()));

        // desc mirrors asc for a rendered column; created_by_name accepted as a
        // whitelist key (alias sort over the joined display name).
        PageResult<ReportRecordInfo> byNameDesc = reportService.list(
                new ReportQuery(pid, null, 1, 10, "created_by_name", "desc"));
        assertEquals(4, byNameDesc.total());
        PageResult<ReportRecordInfo> byTitleDesc = reportService.list(
                new ReportQuery(pid, null, 1, 10, "title", "desc"));
        assertEquals(List.of("ZZZ 报告", "MMM 报告", "MMM 报告", "AAA 报告"),
                byTitleDesc.list().stream().map(ReportRecordInfo::title).toList());

        // Sort composes with the keyword filter: MMM segment ordered by range_type desc.
        PageResult<ReportRecordInfo> filtered = reportService.list(
                new ReportQuery(pid, "MMM", 1, 10, "range_type", "desc"));
        assertEquals(2, filtered.total());
        assertEquals(2, filtered.list().get(0).rangeType(), "time-range row leads under desc");

        // Fail-closed refusals (before any read): the payload blob column and an
        // injection-shaped key both get a 400 naming the allowed set.
        ServiceException offList = assertThrows(ServiceException.class,
                () -> reportService.list(new ReportQuery(pid, null, 1, 10, "report_json", "asc")));
        assertTrue(offList.getMessage().contains("report_json"), "报错点名非法列");
        assertTrue(offList.getMessage().contains("created_at"), "报错含允许清单");
        ServiceException injection = assertThrows(ServiceException.class,
                () -> reportService.list(new ReportQuery(pid, null, 1, 10,
                        "id; DROP TABLE tp_test_report", "asc")));
        assertTrue(injection.getMessage().contains("title"), "注入形态同样拒且点名白名单");

        // Refusals left nothing behind: the window still answers the default order.
        assertEquals(4, reportService.list(new ReportQuery(pid, null, 1, 10)).total());
        // Foreign segment untouched by project A's ordering keys: still one row.
        assertEquals(1, reportService.list(new ReportQuery(other, null, 1, 10, "title", "asc")).total());
    }
}
