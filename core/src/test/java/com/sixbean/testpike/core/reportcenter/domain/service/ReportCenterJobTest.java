package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportGenerateService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportRetentionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>R37 fixture contract: teardown deletes only run-owned rows (tracked
 * project ids + id-watermark sweeps) — the old blanket DELETE/TRUNCATE wiped
 * shipped snapshots and the 40+ projects of the shared dev database. Whole-
 * table expectations are re-anchored on the live active-project count and on
 * the run watermark, keeping the same per-active-project+global semantics.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class ReportCenterJobTest {

    @Autowired
    private ReportGenerateService reportGenerateService;

    @Autowired
    private ReportRetentionService reportRetentionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private static final long CREATOR = 9601L;
    
    private static final long ADMIN = 9602L;

    /** Run-owned fixture ledger (R37): tracked project ids + id watermarks. */
    private final List<Long> projectIds = new ArrayList<>();
    private long reportIdFloor;
    private long notificationIdFloor;
    private String runToken;

    @BeforeEach
    void markRunWatermarks() {
        runToken = SharedDbFixtureSweep.newToken();
        reportIdFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_report");
        notificationIdFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");
        // Crash-leftover guard: stale REPORT_DIGEST notifications of the
        // fixture users would otherwise suppress this run via dedup keys.
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type = 'REPORT_DIGEST'"
                + " AND user_id IN (SELECT id FROM tp_user WHERE username LIKE 'k4job_%')");
        // Reset hot-reload switches (tests stay independent, no cross-state).
        configCacheHolder.put("report.weekly-enabled", "true");
        configCacheHolder.put("report.monthly-enabled", "true");
        configCacheHolder.put("report.retention-days", "365");
    }

    @AfterEach
    void sweepRunOwnedRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_report", reportIdFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_notification", notificationIdFloor);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
        jdbcTemplate.update("DELETE FROM tp_notification_pref WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'k4job_%')");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'k4job_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'k4job_%'");
    }

    private void ensureUser(long id, int role) {
        jdbcTemplate.update("INSERT INTO tp_user (id, username, password_hash, role, status) "
                + "VALUES (?, 'k4job_user' || ?, NULL, ?, 1) ON CONFLICT (id) DO NOTHING", id, id, role);
    }

    private void subscribeDigest(long userId, int enabled) {
        jdbcTemplate.update("INSERT INTO tp_notification_pref (user_id, event_type, enabled) "
                        + "VALUES (?, 'REPORT_DIGEST', ?) ON CONFLICT (user_id, event_type) DO UPDATE SET enabled = ?",
                userId, enabled, enabled);
    }

    
    private long createProject(String code) {
        ensureUser(CREATOR, 1);
        long id = projectService.create(CREATOR, new ProjectCreateInput("K4项目" + code,
                code + "_" + runToken, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private LocalDateTime dbTs(String expr) {
        return jdbcTemplate.queryForObject("SELECT " + expr, Timestamp.class).toLocalDateTime();
    }

    private long auditMaxId() {
        Long v = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(id), 0) FROM tp_audit_log", Long.class);
        return v == null ? 0L : v;
    }

    private int reportCount(String extraWhere) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_report WHERE 1 = 1" + extraWhere, Integer.class);
        return c == null ? 0 : c;
    }

    /** Live anchor (R37): active projects of the shared database, shipped rows included. */
    private int activeProjectCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE status = 1", Integer.class);
        return c == null ? 0 : c;
    }


    @Test
    void weeklyLegGeneratesPerActiveProjectPlusGlobal() {
        long p1 = createProject("K4A");
        long p2 = createProject("K4B");
        long archived = createProject("K4C");
        projectService.archive(archived);

        int activeTotal = activeProjectCount();
        int generated = reportGenerateService.runWeekly();

        assertEquals(activeTotal + 1, generated, "逐活跃项目行+1 全局维行（共享库=在库活跃项目数）");
        LocalDateTime weekStart = dbTs("date_trunc('week', now() - interval '7 days')");
        LocalDateTime weekEnd = dbTs("date_trunc('week', now())");
        assertEquals(1, reportCount(" AND scope_type = 1 AND scope_id = " + p1
                + " AND report_type = 1 AND period_start = TIMESTAMP '" + weekStart + "'"));
        assertEquals(1, reportCount(" AND scope_type = 1 AND scope_id = " + p2));
        assertEquals(1, reportCount(" AND scope_type = 2 AND scope_id = 0"
                + " AND period_start = TIMESTAMP '" + weekStart + "'"
                + " AND period_end = TIMESTAMP '" + weekEnd + "'"), "全局维 scope_id=0（V53 裁定②）");
        assertEquals(0, reportCount(" AND scope_type = 1 AND scope_id = " + archived), "归档项目不发");
    }

    @Test
    void monthlyLegUsesPreviousNaturalMonth() {
        createProject("K4M");
        int activeTotal = activeProjectCount();
        int generated = reportGenerateService.runMonthly();

        assertEquals(activeTotal + 1, generated, "逐活跃项目行+1 全局行（共享库=在库活跃项目数）");
        LocalDateTime monthStart = dbTs("date_trunc('month', now() - interval '1 month')");
        LocalDateTime monthEnd = dbTs("date_trunc('month', now())");
        // Previous natural month = one row per active project plus the global
        // row (shared-db anchor = live active count); the window bounds are
        // the assertion surface.
        assertEquals(activeTotal + 1, reportCount(" AND report_type = 2"
                + " AND period_start = TIMESTAMP '" + monthStart + "'"
                + " AND period_end = TIMESTAMP '" + monthEnd + "'"));
    }

    @Test
    void disabledSwitchStopsGenerationHotReload() {
        createProject("K4S");
        configCacheHolder.put("report.weekly-enabled", "false");
        assertEquals(0, reportGenerateService.runWeekly(), "开关 false=热更即停发（K8）");
        assertEquals(0, reportCount(" AND id > " + reportIdFloor), "停发期间本 run 零新行（水位口径）");
        configCacheHolder.put("report.weekly-enabled", "true");
        assertEquals(activeProjectCount() + 1, reportGenerateService.runWeekly(), "热更回 true 即恢复");
        configCacheHolder.put("report.monthly-enabled", "0");
        assertEquals(0, reportGenerateService.runMonthly(), "值兼容 0/1 解析（SystemConfigReader.getBoolean）");
    }


    @Test
    void digestDefaultOffFilteredAndSubscribedDeliversHumanReadable() {
        createProject("K4D");
        reportGenerateService.runWeekly();
        assertEquals(0, digestCount(" AND user_id IN (" + CREATOR + ", " + ADMIN + ")"),
                "REPORT_DIGEST=default_off，未订阅不发");

        subscribeDigest(CREATOR, 1);
        ensureUser(ADMIN, 4);
        subscribeDigest(ADMIN, 1);
        reportGenerateService.runWeekly();
        assertEquals(1, digestCount(" AND user_id = " + CREATOR), "项目负责人∪创建人=CREATOR 收项目摘要");
        assertEquals(1, digestCount(" AND user_id = " + ADMIN), "全局维摘要发系统管理员");
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE user_id = " + CREATOR
                        + " AND event_type = 'REPORT_DIGEST'", String.class);
        assertTrue(content.contains("通过率") && content.contains("缺陷密度"),
                "摘要人话含关键口径值：" + content);
    }

    @Test
    void regeneratedReportDigestDedupedNoDoubleSend() {
        createProject("K4R");
        subscribeDigest(CREATOR, 1);
        int expectedRows = activeProjectCount() + 1;
        int first = reportGenerateService.runWeekly();
        int second = reportGenerateService.runWeekly();
        assertEquals(expectedRows, first);
        assertEquals(expectedRows, second);
        assertEquals(1, digestCount(" AND user_id = " + CREATOR), "重生成=dedup 不重发");
    }


    @Test
    void retentionPurgesExpiredOnlyWithAuditTrace() {
        createProject("K4P");
        reportGenerateService.runWeekly();
        long auditBefore = auditMaxId();
        LocalDateTime weekStart = dbTs("date_trunc('week', now() - interval '7 days')");
        LocalDateTime weekEnd = dbTs("date_trunc('week', now())");
        // Age out only this run's project rows (watermark-scoped UPDATE): the
        // shipped snapshot and other suites' rows are never mutated.
        int ownedProjectRows = reportCount(" AND scope_type = 1 AND id > " + reportIdFloor);
        assertTrue(ownedProjectRows >= 1, "本 run 至少应有 1 条项目维快照");
        jdbcTemplate.update("UPDATE tp_report SET generated_at = now() - interval '400 days'"
                + " WHERE scope_type = 1 AND id > ?", reportIdFloor);

        int deleted = reportRetentionService.deleteExpired();
        assertEquals(ownedProjectRows, deleted, "默认 365 天：只删过期的本 run 项目行");
        assertEquals(1, reportCount(" AND scope_type = 2"
                + " AND period_start = TIMESTAMP '" + weekStart + "'"
                + " AND period_end = TIMESTAMP '" + weekEnd + "'"), "未过期全局行不误伤");

        Integer audits = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log"
                        + " WHERE object_type = 'report' AND action = 'retention-purge' AND id > ?",
                Integer.class, auditBefore);
        assertEquals(1, audits, "清理动作进审计一条");

        configCacheHolder.put("report.retention-days", "0");
        assertEquals(0, reportRetentionService.deleteExpired(), "0=不限保留守卫（宁多保留不误删，Q6 同哲学）");
        // Re-seed run-owned snapshots, age them past a tightened window and
        // assert the purge on the watermark ledger only — a fixture run must
        // never count or evict shipped rows on the shared database.
        reportGenerateService.runWeekly();
        int ownedRows = reportCount(" AND id > " + reportIdFloor);
        configCacheHolder.put("report.retention-days", "7");
        jdbcTemplate.update("UPDATE tp_report SET generated_at = now() - interval '10 days'"
                + " WHERE id > ?", reportIdFloor);
        assertEquals(ownedRows, reportRetentionService.deleteExpired(), "热更收紧后过期行即删");
        assertEquals(0, reportCount(" AND id > " + reportIdFloor), "本 run 快照清账归零（水位口径）");
    }

    private int digestCount() {
        return digestCount("");
    }

    private int digestCount(String extraWhere) {
        Integer c = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_notification"
                + " WHERE event_type = 'REPORT_DIGEST'" + extraWhere, Integer.class);
        return c == null ? 0 : c;
    }
}
