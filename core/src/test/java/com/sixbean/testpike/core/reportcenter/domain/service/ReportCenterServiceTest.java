package com.sixbean.testpike.core.reportcenter.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>R37 fixture contract: teardown removes only run-owned rows (tracked
 * fixture projects, tp_exec_task swept through the project FK sweep, and
 * tp_report ids above the per-test watermark). "No duplicate row" assertions
 * count the UNIQUE snapshot key (type+scope+period), which is exactly the
 * idempotency contract — not the whole table.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class ReportCenterServiceTest {

    @Autowired
    private ReportCenterService reportCenterService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    
    private LocalDateTime weekStart;
    private LocalDateTime weekEnd;

    /** Run-owned fixture ledger (R37): tracked project ids + tp_report watermark. */
    private final List<Long> projectIds = new ArrayList<>();
    private long reportIdFloor;
    private String runToken;

    @BeforeEach
    void markWatermarkAndAnchor() {
        runToken = SharedDbFixtureSweep.newToken();
        reportIdFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_report");
        Timestamp start = jdbcTemplate.queryForObject(
                "SELECT date_trunc('week', now() - interval '7 days')", Timestamp.class);
        Timestamp end = jdbcTemplate.queryForObject(
                "SELECT date_trunc('week', now())", Timestamp.class);
        weekStart = start.toLocalDateTime();
        weekEnd = end.toLocalDateTime();
    }

    @AfterEach
    void sweepRunOwnedRows() {
        SharedDbFixtureSweep.reportsAndProjects(jdbcTemplate, reportIdFloor, projectIds);
    }

    
    private void seedTask(long projectId, int status, LocalDateTime createdAt) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status, created_at)"
                + " VALUES (?, 0, ?, ?)", projectId, status, createdAt);
    }

    private ReportGenerateInput weekly(Long scopeId, String start, String end) {
        return new ReportGenerateInput(1, scopeId == null ? 2 : 1, scopeId, start, end);
    }

    private static String ts(LocalDateTime t) {
        return t.toString();
    }

    /**
     * Snapshot rows for exactly this run's weekly project key. The idempotency
     * contract under test is the UNIQUE (type, scope, period) key, so that is
     * the count anchor on a shared database (R37 — no whole-table wipes).
     */
    private int keyedRow(String extraWhere, Object... args) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_report WHERE 1 = 1" + extraWhere, Integer.class, args);
        return c == null ? 0 : c;
    }


    @Test
    void weeklyGeneratePersistsSnapshotWithPeriodAndCaliberVersions() throws Exception {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RPT1");
        seedTask(pid, 3, weekStart.plusDays(1));
        seedTask(pid, 3, weekStart.plusDays(2));
        seedTask(pid, 4, weekStart.plusDays(3));
        seedTask(pid, 4, weekStart.plusDays(4));
        seedTask(pid, 3, weekEnd.plusDays(1));

        ReportView view = reportCenterService.generate(1L, weekly(pid, ts(weekStart), ts(weekEnd)));

        assertNotNull(view.id(), "生成后应返回快照 ID");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM tp_report WHERE id = ?", view.id());
        assertEquals(1, ((Number) row.get("scope_type")).intValue());
        assertEquals(pid, ((Number) row.get("scope_id")).longValue());
        assertEquals(1, ((Number) row.get("report_type")).intValue());
        assertEquals(Timestamp.valueOf(weekStart), row.get("period_start"), "期间起=传入周一");
        assertEquals(Timestamp.valueOf(weekEnd), row.get("period_end"), "期间止=次周一（不含）");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) objectMapper.readValue(
                jdbcTemplate.queryForObject("SELECT payload::text FROM tp_report WHERE id = ?",
                        String.class, view.id()),
                Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> calibers = (Map<String, Object>) payload.get("calibers");
        for (String code : List.of("pass-rate", "defect-density", "req-coverage",
                "ai-accept-rate", "exec-p95", "token-cost-window",
                "feedback-reject-type", "fix-adopt-rate")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) calibers.get(code);
            assertNotNull(entry, "payload 缺口径块: " + code);
            assertNotNull(entry.get("version"), code + " 未内嵌口径版本号（TC-O10-02）");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> passRate = (Map<String, Object>) calibers.get("pass-rate");
        assertEquals(0.5, ((Number) passRate.get("value")).doubleValue(), 1e-9,
                "本期通过率=2/4=0.5（期间外任务不串入），实际=" + passRate.get("value"));
    }


    @Test
    void regenerateSameKeyOverwritesSingleRowWithAudit() throws Exception {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RPT2");
        seedTask(pid, 3, weekStart.plusDays(1));
        ReportView first = reportCenterService.generate(7L, weekly(pid, ts(weekStart), ts(weekEnd)));

        seedTask(pid, 4, weekStart.plusDays(2));
        ReportView second = reportCenterService.generate(7L, weekly(pid, ts(weekStart), ts(weekEnd)));

        assertEquals(first.id(), second.id(), "同键重生成=覆盖同一行，实际=" + keyedRow(
                " AND report_type = 1 AND scope_type = 1 AND scope_id = ?"
                        + " AND period_start = ? AND period_end = ?",
                pid, Timestamp.valueOf(weekStart), Timestamp.valueOf(weekEnd)) + " 行");
        Integer rows = keyedRow(" AND report_type = 1 AND scope_type = 1 AND scope_id = ?"
                + " AND period_start = ? AND period_end = ?",
                pid, Timestamp.valueOf(weekStart), Timestamp.valueOf(weekEnd));
        assertEquals(1, rows, "重复生成出现重复行（TC-O10-03，同键唯一行口径）");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) objectMapper.readValue(
                second.payloadJson(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> passRate =
                (Map<String, Object>) ((Map<String, Object>) payload.get("calibers")).get("pass-rate");
        assertEquals(0.5, ((Number) passRate.get("value")).doubleValue(), 1e-9,
                "覆盖后 payload 未刷新（仍是旧值）");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'report'"
                        + " AND action = 'generate' AND object_id = ?",
                Integer.class, String.valueOf(first.id()));
        assertEquals(2, auditCount, "两次生成应有 2 条审计（幂等覆盖+@Audit，TC-O10-03）");
    }


    @Test
    void invalidPeriodRejectedWithHumanMessage() {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RPT3");

        ServiceException badFormat = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L, weekly(pid, "2026-09-07", ts(weekEnd))));
        assertTrue(badFormat.getMessage().contains("期间"), "报错应人话点名期间格式: " + badFormat.getMessage());

        ServiceException reversed = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L, weekly(pid, ts(weekEnd), ts(weekStart))));
        assertTrue(reversed.getMessage().contains("早于") || reversed.getMessage().contains("倒序"),
                "倒序报错应点名边界次序: " + reversed.getMessage());

        LocalDateTime nextMonday = weekEnd.plusWeeks(1);
        ServiceException future = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L, weekly(pid,
                        ts(nextMonday), ts(nextMonday.plusDays(7)))));
        assertTrue(future.getMessage().contains("未来"), "未来期报错应点名: " + future.getMessage());

        ServiceException notMonday = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L, weekly(pid,
                        ts(weekStart.plusDays(1)), ts(weekEnd.plusDays(1)))));
        assertTrue(notMonday.getMessage().contains("周一"), "非周一起算应人话点名: " + notMonday.getMessage());

        ServiceException badMonth = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L,
                        new ReportGenerateInput(2, 1, pid, ts(weekStart), ts(weekStart.plusDays(7)))));
        assertTrue(badMonth.getMessage().contains("自然月"), "月报非法跨度应点名自然月: " + badMonth.getMessage());

        // Fresh fixture project ⇒ any snapshot row scoped to it is run-owned;
        // the no-half-row pin keeps its full strength without a table wipe.
        assertTrue(keyedRow(" AND scope_type = 1 AND scope_id = ?", pid) == 0,
                "值域拒绝不得留下半行（IT-O10-02 无半行断言）");
    }

    @Test
    void invalidTypeAndProjectRejected() {
        assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L,
                        new ReportGenerateInput(3, 1, 1L, ts(weekStart), ts(weekEnd))));
        assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L,
                        new ReportGenerateInput(1, 1, null, ts(weekStart), ts(weekEnd))));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> reportCenterService.generate(1L,
                        new ReportGenerateInput(1, 1, 999_999_999L, ts(weekStart), ts(weekEnd))));
        assertTrue(missing.getMessage().contains("项目不存在"), missing.getMessage());
    }


    @Test
    void globalScopeUsesZeroIdAndOverwritesIdempotently() {
        ReportView first = reportCenterService.generate(1L, weekly(null, ts(weekStart), ts(weekEnd)));
        ReportView second = reportCenterService.generate(1L, weekly(null, ts(weekStart), ts(weekEnd)));
        assertEquals(first.id(), second.id(), "全局维覆盖重生成幂等失效（scope_id NULL 陷阱，V53 裁定②）");
        Long scopeId = jdbcTemplate.queryForObject(
                "SELECT scope_id FROM tp_report WHERE id = ?", Long.class, first.id());
        assertEquals(0L, scopeId, "全局维 scope_id 必须=0 禁 NULL");
        // Idempotency anchor: exactly one row per UNIQUE (type, scope, period)
        // key (the global weekly key is naturally unique, shipped rows with
        // the same key included).
        assertEquals(1, keyedRow(" AND report_type = 1 AND scope_type = 2 AND scope_id = 0"
                + " AND period_start = ? AND period_end = ?",
                Timestamp.valueOf(weekStart), Timestamp.valueOf(weekEnd)),
                "全局维同键快照恰好一行（V53 裁定②幂等面）");
    }


    @Test
    void concurrentGenerate_sameKey_singleRowNoHalf() throws Exception {
        long pid = SharedDbFixtureSweep.createServiceProject(projectService, projectIds, runToken, "RPT4");
        seedTask(pid, 3, weekStart.plusDays(1));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        reportCenterService.generate(1L, weekly(pid, ts(weekStart), ts(weekEnd)));
                        success.incrementAndGet();
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发生成 60s 内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failed.get(), "并发覆盖不应有失败（ON CONFLICT DO UPDATE 行锁串行化），failed="
                + failed.get());
        assertEquals(threads, success.get());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_report WHERE scope_type = 1 AND scope_id = ?",
                Integer.class, pid);
        assertEquals(1, rows, "并发幂等=单行覆盖，无重复行/半行（IT-O10-02）");
    }
}
