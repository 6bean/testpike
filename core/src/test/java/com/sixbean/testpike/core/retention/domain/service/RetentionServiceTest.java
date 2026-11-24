package com.sixbean.testpike.core.retention.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.retention.api.dto.RetentionItem;
import com.sixbean.testpike.core.support.FixtureInserts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionServiceTest {

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ConfigCacheHolder configCache;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    
    private static final int VIDEO_DAYS = 90;
    private static final int AUDIT_DAYS = 365;
    private static final int EXEC_DAYS = 180;
    private static final int SAMPLE_DAYS = 730;
    /** Perf run retention window (perf.run.retention-days; golden-marked runs are exempt). */
    private static final String PERF_RUN_KEY = "perf.run.retention-days";
    private static final int PERF_RUN_DAYS = 90;

    
    private static final String GATE_KEY = "tp.audit_retention_allowed";

    private final List<Long> seededTaskIds = new ArrayList<>();
    private final List<Long> seededSampleIds = new ArrayList<>();
    private final List<Long> seededPerfRunIds = new ArrayList<>();
    /** Ambient projects created by this class only when tp_project is empty (new chain slot). */
    private final List<Long> seededProjectIds = new ArrayList<>();

    
    @BeforeAll
    @AfterAll
    void resetAuditBaseline() {
        inGatedTx(() -> {
            jdbcTemplate.update("DELETE FROM tp_audit_retention_break");
            jdbcTemplate.update("DELETE FROM tp_audit_log");
        });
    }

    @BeforeEach
    void seedRetentionConfigs() {
        setRetention("retention.video-days", String.valueOf(VIDEO_DAYS));
        setRetention("retention.audit-days", String.valueOf(AUDIT_DAYS));
        setRetention("retention.exec-history-days", String.valueOf(EXEC_DAYS));
        setRetention("retention.training-sample-days", String.valueOf(SAMPLE_DAYS));
        setRetention(PERF_RUN_KEY, String.valueOf(PERF_RUN_DAYS));
    }

    @AfterEach
    void restoreDefaults() {
        setRetention("retention.video-days", "90");
        setRetention("retention.audit-days", "365");
        setRetention("retention.exec-history-days", "180");
        setRetention("retention.training-sample-days", "730");
        setRetention(PERF_RUN_KEY, "90");
        for (Long id : seededSampleIds) {
            jdbcTemplate.update("DELETE FROM tp_training_sample WHERE id = ?", id);
        }
        for (Long id : seededTaskIds) {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE id = ?", id);
        }
        for (Long id : seededPerfRunIds) {
            jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE run_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE run_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_perf_run WHERE id = ?", id);
        }
        // Ambient projects ride last: child rows above already released the FK refs.
        for (Long id : seededProjectIds) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", id);
        }
        seededSampleIds.clear();
        seededTaskIds.clear();
        seededPerfRunIds.clear();
        seededProjectIds.clear();
    }

    
    private void setRetention(String key, String value) {
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?", value, key);
        configCache.put(key, value);
    }

 
    private Long anyProjectId() {
        List<Long> ids = jdbcTemplate.query("SELECT id FROM tp_project ORDER BY id LIMIT 1",
                (rs, n) -> rs.getLong(1));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        // Empty tp_project (freshly provisioned chain slot): the 0L fallback breaks
        // tp_perf_run_project_id_fkey, so create one ambient project through the same
        // INSERT ... RETURNING shape as PerfScheduleFireServiceTest and reclaim it by
        // id in @AfterEach (R37: self-created rows, no factory-table trust).
        long uniq = System.nanoTime() % 1_000_000_000L;
        Long created = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "retention-ambient-project-" + uniq, "RETENTION" + uniq);
        seededProjectIds.add(created);
        return created;
    }

    private long seedTask(int status, LocalDateTime createdAt, String videoKey, String shotsJson) {
        long id = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_exec_task (project_id, case_id, status, created_at, video_key,"
                        + " screenshots_json) VALUES (?, 0, ?, ?, ?, ?::jsonb)",
                anyProjectId(), status, createdAt, videoKey, shotsJson);
        seededTaskIds.add(id);
        return id;
    }

    private long seedSample(LocalDateTime createdAt) {
        long id = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_training_sample (case_id, sample_type, sample_json,"
                        + " source_version, created_at) VALUES (0, '编辑', '{}'::jsonb, 1, ?)", createdAt);
        seededSampleIds.add(id);
        return id;
    }

    /**
     * Seed a finished perf run whose finished_at falls before the retention cutoff.
     * Status 2 = success (terminal); mark stays NULL unless the caller marks it
     * golden. Children (metric + sample rows) ride along so the delete order is
     * exercised against real foreign keys.
     */
    private long seedPerfRun(LocalDateTime finishedAt, String mark) {
        long id = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_perf_run (scenario_id, project_id, status,"
                        + " pressure_snapshot, finished_at, mark) VALUES (NULL, ?, 2, '{}'::jsonb, ?, ?)",
                anyProjectId(), finishedAt, mark);
        seededPerfRunIds.add(id);
        jdbcTemplate.update("INSERT INTO tp_perf_metric (run_id, project_id, second_offset,"
                + " rps, p95_ms, error_rate) VALUES (?, ?, 0, 1, 1, 0)", id, anyProjectId());
        jdbcTemplate.update("INSERT INTO tp_perf_sample (run_id, project_id, second_offset,"
                + " event_kind, detail) VALUES (?, ?, 0, 'error', 'seed')", id, anyProjectId());
        return id;
    }

    private void recordAudit(String action, Map<String, Object> after) {
        auditLogService.record(new AuditLogInput(1L, null, "retention-test", action, action, null, after));
    }

    
    private void expireAllAuditRows(LocalDateTime when) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SELECT set_config('" + GATE_KEY + "', 'on', true)");
            jdbcTemplate.update("UPDATE tp_audit_log SET created_at = ?", when);
        });
    }

    
    private void inGatedTx(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SELECT set_config('" + GATE_KEY + "', 'on', true)");
            work.run();
        });
    }


    @Test
    void previewMatchesRunExactly() {
        seedTask(3, LocalDateTime.now().minusDays(EXEC_DAYS + 10), null, null);
        long activeExpired = seedTask(2, LocalDateTime.now().minusDays(EXEC_DAYS + 10), null, null);
        long freshTask = seedTask(5, LocalDateTime.now().minusDays(1), null, null);
        long videoTask = seedTask(3, LocalDateTime.now().minusDays(VIDEO_DAYS + 10),
                "retention-test/video-x.mp4", "[\"retention-test/shot-1.png\"]");
        seedSample(LocalDateTime.now().minusDays(SAMPLE_DAYS + 1));
        seedSample(LocalDateTime.now().minusDays(1));

        List<RetentionItem> preview = retentionService.preview();
        assertEquals(5, preview.size(), "五类预览行齐全");
        assertTrue(preview.stream().allMatch(i ->
                        i.configKey().startsWith("retention.") || i.configKey().startsWith("perf.")),
                "键名 retention.* 或 perf.*");
        assertTrue(itemOf(preview, "retention.exec-history-days").count() >= 1, "过期终态任务预览≥1");
        assertTrue(itemOf(preview, "retention.training-sample-days").count() >= 1, "过期样本预览≥1");
        assertTrue(itemOf(preview, "retention.video-days").count() >= 1, "有产物过期任务预览≥1");
        List<RetentionItem> run = retentionService.run();
        assertEquals(preview.size(), run.size(), "预览与执行类别数一致");
        for (int i = 0; i < preview.size(); i++) {
            assertEquals(preview.get(i).configKey(), run.get(i).configKey());
            assertEquals(preview.get(i).count(), run.get(i).count(),
                    preview.get(i).configKey() + " 预览条数必须=实删条数（IT-Q6-01 同参数同结果）");
        }
        assertEquals(0L, countById("tp_exec_task", seededTaskIds.get(0)), "过期终态任务应被清理");
        assertEquals(1L, countById("tp_exec_task", activeExpired), "非终态过期任务不得清理");
        assertEquals(1L, countById("tp_exec_task", freshTask), "未过期任务必须保留");
        assertEquals(1L, countById("tp_exec_task", videoTask), "产物过期但行未到 exec 窗口=保留");
        Long dangling = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE id = ? AND (video_key IS NOT NULL"
                        + " OR screenshots_json IS NOT NULL)", Long.class, videoTask);
        assertEquals(0L, dangling, "产物清理后引用列必须 NULL 化");
        Long samplesKept = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_training_sample WHERE id = ?", Long.class, seededSampleIds.get(1));
        assertEquals(1L, samplesKept, "未过期样本必须保留");
        assertEquals(0L, countById("tp_training_sample", seededSampleIds.get(0)), "过期样本应被清理");
    }

    private Long countById(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
    }


    @Test
    void auditPrefixCleanupKeepsChainVerifiable() {
        expireAllAuditRows(LocalDateTime.now().minusDays(AUDIT_DAYS + 5));
        recordAudit("new-a", Map.of("i", 3));
        assertTrue(auditLogService.verifyChain(), "清理前链完整");

        Long oldBefore = countAuditExpired();
        recordAudit("tail-keep", Map.of("i", 4));
        RetentionItem auditItem = itemOf(retentionService.preview(), "retention.audit-days");
        assertEquals(oldBefore, auditItem.count(), "预览=过期连续前缀条数");

        retentionService.run();

        Long expiredAfter = countAuditExpired();
        assertEquals(0L, expiredAfter, "过期前缀已删");
        Long newKept = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action IN ('new-a','tail-keep')", Long.class);
        assertEquals(2L, newKept, "保护期审计不误删");
        assertTrue(auditLogService.verifyChain(), "前缀清理+账本登记后链校验必须通过（起点感知）");

        Long breaks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_retention_break WHERE removed_count >= ?", Long.class, oldBefore);
        assertTrue(breaks >= 1, "断点账本已登记（removed_through/new_head_hash/removed_count）");

        List<RetentionItem> second = retentionService.run();
        assertEquals(0L, itemOf(second, "retention.audit-days").count(), "重跑无新增过期=0（幂等）");
        Long newKept2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action IN ('new-a','tail-keep')", Long.class);
        assertEquals(2L, newKept2, "重跑仍不误删保护期数据");
        assertTrue(auditLogService.verifyChain(), "重跑后链依然完整");
    }

    @Test
    void verifyChain_detectsMidSegmentDeletionAfterBreak() {
        expireAllAuditRows(LocalDateTime.now().minusDays(AUDIT_DAYS + 5));
        recordAudit("b-new-1", Map.of("i", 1));
        recordAudit("b-new-2", Map.of("i", 2));
        recordAudit("b-new-3", Map.of("i", 3));
        retentionService.run();
        assertTrue(auditLogService.verifyChain(), "清理后起点感知=完整");

        boolean blocked = false;
        try {
            jdbcTemplate.update("DELETE FROM tp_audit_log WHERE action = 'b-new-2'");
        } catch (Exception e) {
            blocked = true;
        }
        assertTrue(blocked, "trigger 必须仍拦截非保留路径 DELETE");
        assertTrue(auditLogService.verifyChain(), "trigger 拦截后链仍完整");

        List<Map<String, Object>> victim = jdbcTemplate.queryForList(
                "SELECT * FROM tp_audit_log WHERE action = 'b-new-2'");
        assertFalse(victim.isEmpty(), "b-new-2 应存在");
        inGatedTx(() -> jdbcTemplate.update("DELETE FROM tp_audit_log WHERE action = 'b-new-2'"));
        assertFalse(auditLogService.verifyChain(), "账本只豁免已登记前缀——中间段删除必须检出");

        Map<String, Object> v = victim.get(0);
        inGatedTx(() -> jdbcTemplate.update("""
                INSERT INTO tp_audit_log
                    (id, operator_id, project_id, object_type, object_id, action,
                     before_json, after_json, prev_hash, cur_hash, created_at)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, v.get("id"), v.get("operator_id"), v.get("project_id"), v.get("object_type"),
                v.get("object_id"), v.get("action"),
                v.get("before_json") == null ? null : String.valueOf(v.get("before_json")),
                v.get("after_json") == null ? null : String.valueOf(v.get("after_json")),
                v.get("prev_hash"), v.get("cur_hash"), v.get("created_at")));
        assertTrue(auditLogService.verifyChain(), "原样恢复后链校验必须回到完整（自证恢复无损）");
    }


    @Test
    void runWritesAuditRecordWithCounts() {
        seedTask(4, LocalDateTime.now().minusDays(EXEC_DAYS + 3), null, null);
        seedSample(LocalDateTime.now().minusDays(SAMPLE_DAYS + 3));
        retentionService.run();
        Long auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'retention' AND action = 'run'",
                Long.class);
        assertTrue(auditRows >= 1, "清理动作必须进审计（PRD Q6）");
        String after = jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log WHERE object_type = 'retention' AND action = 'run'"
                        + " ORDER BY id DESC LIMIT 1",
                String.class);
        assertNotNull(after);
        assertTrue(after.contains("video-days") && after.contains("audit-days")
                        && after.contains("exec-history-days") && after.contains("training-sample-days"),
                "审计 after 快照含四类键与条数: " + after);
    }

    // ── perf-run retention window (golden exempt, terminal-only, children deleted first) ──

    @Test
    void perfRunRetentionKeepsGoldenAndRemovesExpiredWithChildren() {
        long expiredPlain = seedPerfRun(LocalDateTime.now().minusDays(PERF_RUN_DAYS + 5), null);
        long expiredGolden = seedPerfRun(LocalDateTime.now().minusDays(PERF_RUN_DAYS + 5), "GOLDEN");
        long expiredVoid = seedPerfRun(LocalDateTime.now().minusDays(PERF_RUN_DAYS + 5), "VOID");
        long freshRun = seedPerfRun(LocalDateTime.now().minusDays(1), null);

        RetentionItem perfItem = itemOf(retentionService.preview(), PERF_RUN_KEY);
        // Only expired non-golden rows qualify: plain + VOID count, GOLDEN is exempt,
        // the fresh run sits inside the window.
        assertEquals(2L, perfItem.count(), "预览=越窗非金跑条数（金跑豁免不误计）");

        retentionService.run();

        assertEquals(0L, countById("tp_perf_run", expiredPlain), "越窗普通终态运行应清理");
        assertEquals(0L, countById("tp_perf_run", expiredVoid), "VOID 标记同属非金跑照样清理");
        assertEquals(1L, countById("tp_perf_run", expiredGolden), "金跑永久保留（清退豁免）");
        assertEquals(1L, countById("tp_perf_run", freshRun), "未越窗运行必须保留");
        Long childMetrics = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_metric WHERE run_id = ?", Long.class, expiredPlain);
        assertEquals(0L, childMetrics, "被删运行的指标子行随删（先子后父零 FK 残留）");
        Long childSamples = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_sample WHERE run_id = ?", Long.class, expiredPlain);
        assertEquals(0L, childSamples, "被删运行的样本子行随删");
        // Re-run is idempotent: nothing newly expired for the perf class.
        assertEquals(0L, itemOf(retentionService.run(), PERF_RUN_KEY).count(), "重跑幂等=0");
    }


    @Test
    void retentionConfigRejectsNonPositive() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = 'retention.video-days'", Long.class);
        var input = new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                "retention.video-days", "0", "系统", "int", 0, "录屏保留天数（Q6）");
        ServiceException zero = assertThrows(ServiceException.class,
                () -> configService.update(1L, id, input));
        assertEquals(BizErrorCode.VALIDATION_ERROR, zero.getCode(), "0 → 400（IT-Q6-01）");
        var neg = new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                "retention.video-days", "-5", "系统", "int", 0, "录屏保留天数（Q6）");
        assertThrows(ServiceException.class, () -> configService.update(1L, id, neg));
        var text = new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                "retention.video-days", "abc", "系统", "int", 0, "录屏保留天数（Q6）");
        ServiceException bad = assertThrows(ServiceException.class,
                () -> configService.update(1L, id, text));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bad.getCode(), "非数字 → 400");
        var ok = new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                "retention.video-days", "90", "系统", "int", 0, "录屏保留天数（Q6）");
        assertNotNull(configService.update(1L, id, ok));
    }


    private Long countAuditExpired() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE created_at < now() - (? || ' days')::interval",
                Long.class, AUDIT_DAYS);
    }

    private RetentionItem itemOf(List<RetentionItem> items, String key) {
        return items.stream().filter(i -> i.configKey().equals(key)).findFirst().orElseThrow();
    }
}
