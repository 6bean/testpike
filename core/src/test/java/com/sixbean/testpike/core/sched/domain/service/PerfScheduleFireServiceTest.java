package com.sixbean.testpike.core.sched.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.sched.domain.service.ScheduleFireService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduled periodic pressure-test firing chain test (B-8 periodic capacity regressions).
 * <p>The minute scanner (scheduleTriggerScan, no new job name — the existing I2 scan
 * gains a third candidate family) must launch perf scenarios through the single
 * {@code PerfRunService.launch} point, i.e. the exact same safety gate, duration
 * ceiling and GA-2 environment-lock spectrum as the manual and CI doors (zero bypass).
 * <p>Covers: enabled + cron scenario fires at the theoretical trigger point (run row
 * created, ledger success with the run id named); same trigger point scanned twice is
 * idempotent (one run only); disabled or blank-cron scenarios never fire and leave no
 * ledger; a gate rejection is recorded as a failed ledger with the human-readable
 * reason and zero run residue; a pinned schedule_env_id overrides the scenario default
 * environment inside the launch-pinned snapshot (GB-1 semantics through the scheduled
 * door). Real dev-database chain, no mocks (strict testing standard).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfScheduleFireServiceTest {

    /** Daily 02:00 trigger — with now=02:30 the previous fire point is deterministic. */
    private static final String DAILY_02 = "0 0 2 * * *";
    private static final Instant NOW = at("2026-10-25T02:30:00");

    @Autowired
    private ScheduleFireService scheduleFireService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long envBId;

    @BeforeEach
    @AfterEach
    void cleanAndSeed() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "定时压测测试项目_" + uniq, "PERFSCHED" + uniq);
        // One environment per type inside a project is a product rule — dev(1) + test(2).
        envId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '性能-定时dev', 1, 'http://perf-target.local/api')", projectId);
        envBId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '性能-定时test', 2, 'http://perf-target.local/api')", projectId);
    }

    private static Instant at(String localDateTime) {
        return Timestamp.valueOf(LocalDateTime.parse(localDateTime)).toInstant();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_schedule_fire WHERE kind = 'perf' AND project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFSCHED%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFSCHED%'");
    }

    /** Whitelist ceiling row for an environment (maxConc controls gate pass/rejection). */
    private void whitelist(long env, int maxConc) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, ?, '[]'::jsonb)", projectId, env, maxConc);
    }

    /** Scenario row with the scheduled-fire columns (the interactive save face lands in a follow-up segment). */
    private long scenario(String name, long env, String cron, int enabled, Long scheduleEnv) {
        return FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_perf_scenario (project_id, env_id, name, pressure_model, " + "pressure_config, steps_json, schedule_cron, schedule_enabled, schedule_env_id) " + "VALUES (?, ?, ?, 'CONSTANT_RPS', " + "'{\"targetRps\": 40, \"durationSeconds\": 60}'::jsonb, " + "'[{\"action\": \"http.request\", \"method\": \"GET\", " + "\"url\": \"http://perf-target.local/api/orders\"}]'::jsonb, ?, ?, ?)", projectId, env, name, cron, enabled, scheduleEnv);
    }

    private Integer runCount(long scenarioId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM tp_perf_run WHERE scenario_id = ?",
                Integer.class, scenarioId);
    }

    private String ledgerStatus(long scenarioId) {
        return jdbcTemplate.query("SELECT status FROM tp_schedule_fire WHERE kind = 'perf' "
                        + "AND ref_id = ? ORDER BY id DESC",
                rs -> rs.next() ? rs.getString(1) : null, scenarioId);
    }

    private String ledgerMessage(long scenarioId) {
        return jdbcTemplate.query("SELECT message FROM tp_schedule_fire WHERE kind = 'perf' "
                        + "AND ref_id = ? ORDER BY id DESC",
                rs -> rs.next() ? rs.getString(1) : null, scenarioId);
    }

    /** Enabled scenario fires through the shared launch gate: run row + success ledger naming the run. */
    @Test
    void 定时启用场景到点发起_运行行落库且留痕success人话点名运行号() {
        whitelist(envId, 1000);
        long scenarioId = scenario("定时容量回归_到点触发", envId, DAILY_02, 1, null);

        int fired = scheduleFireService.scanAndFire(NOW);

        assertTrue(fired >= 1, "本轮扫描必须计入定时压测触发: fired=" + fired);
        assertEquals(1, runCount(scenarioId).intValue(), "到点必须且只发起一次运行");
        assertEquals("success", ledgerStatus(scenarioId));
        String message = ledgerMessage(scenarioId);
        assertNotNull(message);
        assertTrue(message.contains("定时压测"), "留痕 message 人话点名链路: " + message);
        String fireTime = jdbcTemplate.queryForObject("SELECT fire_time::text FROM tp_schedule_fire "
                        + "WHERE kind = 'perf' AND ref_id = ?", String.class, scenarioId);
        assertNotNull(fireTime);
        assertTrue(fireTime.startsWith("2026-10-25 02:00"), "理论触发点=cron 上期 02:00: " + fireTime);
    }

    /** Same trigger point scanned twice (multi-instance/next minute) fires exactly one run. */
    @Test
    void 同一触发点重复扫描_幂等不双发起() {
        whitelist(envId, 1000);
        long scenarioId = scenario("定时容量回归_幂等", envId, DAILY_02, 1, null);

        scheduleFireService.scanAndFire(NOW);
        scheduleFireService.scanAndFire(NOW.plusSeconds(60));

        assertEquals(1, runCount(scenarioId).intValue(), "幂等占位=同触发点绝不双发起");
        Integer ledgerRows = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_schedule_fire "
                + "WHERE kind = 'perf' AND ref_id = ?", Integer.class, scenarioId);
        assertEquals(1, ledgerRows.intValue(), "同触发点只一行留痕");
    }

    /** Disabled or blank-cron scenarios never fire and never touch the ledger. */
    @Test
    void 停用或空cron场景_不触发零留痕() {
        whitelist(envId, 1000);
        long off = scenario("定时停用样例", envId, DAILY_02, 0, null);
        long blank = scenario("空cron样例", envId, null, 1, null);

        scheduleFireService.scanAndFire(NOW);

        assertEquals(0, runCount(off).intValue());
        assertEquals(0, runCount(blank).intValue());
        assertNull(ledgerStatus(off), "未启用的场景不得有留痕");
        assertNull(ledgerStatus(blank), "空 cron 的场景不得有留痕");
    }

    /** Safety-gate rejection lands as a failed ledger with the human reason and zero run residue. */
    @Test
    void 安全闸拒绝_留痕failed人话原因且零运行残留() {
        whitelist(envId, 1); // ceiling below the scenario's 40 RPS — the gate must refuse
        long scenarioId = scenario("定时越限样例", envId, DAILY_02, 1, null);

        scheduleFireService.scanAndFire(NOW);

        assertEquals(0, runCount(scenarioId).intValue(), "闸内回滚=不得留下运行行");
        assertEquals("failed", ledgerStatus(scenarioId));
        String message = ledgerMessage(scenarioId);
        assertTrue(message != null && message.contains("安全闸"),
                "失败留痕必须带安全闸人话原因: " + message);
    }

    /** Pinned schedule environment overrides the scenario default inside the launch snapshot (GB-1). */
    @Test
    void 定时钉住环境覆盖场景默认_快照envId为钉住环境() {
        whitelist(envId, 1000);
        whitelist(envBId, 1000);
        long scenarioId = scenario("定时环境覆盖样例", envId, DAILY_02, 1, envBId);

        scheduleFireService.scanAndFire(NOW);

        assertEquals(1, runCount(scenarioId).intValue());
        String snapshot = jdbcTemplate.queryForObject("SELECT pressure_snapshot::text FROM tp_perf_run "
                + "WHERE scenario_id = ?", String.class, scenarioId);
        assertNotNull(snapshot);
        // jsonb::text rendering puts a space after the colon (PostgreSQL normalization)
        assertTrue(snapshot.contains("\"envId\": " + envBId),
                "快照必须钉住定时环境（非场景默认）: " + snapshot);
    }
}
