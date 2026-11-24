package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Launch-time enforcement of the perf safety gate (A-1) plus persisted isolation mode (A-5).
 * <p>Gap pinned by this class: before the fix, the access gate was only wired to the precheck
 * endpoint (a UX affordance), so a caller that skipped the wizard and hit the launch endpoint
 * directly could press production with no whitelist, no isolation declaration and no approval.
 * The gate must therefore run inside the launch transaction: a rejection rolls the whole launch
 * back (no status=1 orphan run row, no dispatch task).
 * <p>A-5: the declared isolation mode is now part of the scenario (persisted column), travels
 * into the launch-time pressure snapshot verbatim, and is the value the gate checks — no more
 * wizard-only in-memory truth.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture follows
 * PerfRunLaunchDispatchTest / PerfAccessGateTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunLaunchGateTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfApprovalService perfApprovalService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long devEnvId;
    private long prodEnvId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "发起闸测试项目_" + uniq, "PFGATE" + uniq);
        devEnvId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '开发-发起闸', 1, 'http://perf-target.local/api')", projectId);
        prodEnvId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '生产-发起闸', 4, 'https://erp.prod.internal/api')", projectId);
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFGATE%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFGATE%'");
    }

    /** Config write via ConfigService = cache synchronously invalidated (raw SQL update would
     *  leave a dirty ConfigCacheHolder entry poisoning later cases — known lesson). */
    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "perf gate seed managed by launch-gate test"));
    }

    private void addWhitelist(long envId, int maxConcurrency) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, ?, CAST(? AS jsonb))",
                projectId, envId, maxConcurrency, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId, String name, int targetRps, String isolationMode) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", targetRps, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", envId == prodEnvId
                                ? "https://erp.prod.internal/api/orders"
                                : "http://perf-target.local/api/orders")),
                null, isolationMode));
    }

    /** A-1 red anchor (was green before the fix): skipping the wizard and launching a
     *  production scenario directly must be rejected by the gate — global switch off. */
    @Test
    void launch_生产场景跳向导直打_安全闸拒单_零残留() {
        long scenarioId = newScenario(prodEnvId, "跳向导直打生产", 50, "SHADOW_TABLE");

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));
        assertTrue(e.getMessage().contains("全局开关"),
                "拒单人话必须点名命中闸与可动作指引: " + e.getMessage());

        Integer runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, runs, "闸拒必须整单回滚，不得留 status=1 孤儿运行行");
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, tasks, "闸拒不得留下派发任务");
    }

    /** A-1/A-5: production launch without a declared isolation mode is rejected by name
     *  (declaration now lives on the scenario column, not only in the wizard request). */
    @Test
    void launch_生产场景未申报隔离模式_点名缺位拒单() {
        restoreSeed("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 100);

        long scenarioId = newScenario(prodEnvId, "未申报隔离生产场景", 50, null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));
        assertTrue(e.getMessage().contains("隔离模式"),
                "必须点名隔离模式缺位: " + e.getMessage());
    }

    /** A-1: whitelist cap applies at launch too — concurrency above the filed cap is rejected. */
    @Test
    void launch_并发超白名单备案上限_拒单() {
        addWhitelist(devEnvId, 20);
        long scenarioId = newScenario(devEnvId, "超上限场景", 50, null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenarioId, null));
        assertTrue(e.getMessage().contains("超过该环境备案上限"),
                "拒单必须点名档位与上限对照: " + e.getMessage());
    }

    /** Happy path unchanged: whitelisted non-production launch passes and the declared
     *  isolation mode travels into the pressure snapshot (the data truth behind report labels). */
    @Test
    void launch_白名单开发场景通过_隔离模式入钉版快照() {
        addWhitelist(devEnvId, 100);
        long scenarioId = newScenario(devEnvId, "带申报开发场景", 40, "SHADOW_TABLE");

        long runId = perfRunService.launch(projectId, scenarioId, null);

        String snapshot = jdbcTemplate.queryForObject(
                "SELECT pressure_snapshot::text FROM tp_perf_run WHERE id = ?",
                String.class, runId);
        assertTrue(snapshot.replaceAll("\\s", "").contains("\"isolationMode\":\"SHADOW_TABLE\""),
                "钉版快照必须携带场景申报的隔离模式: " + snapshot);
    }

    /** A-5 persistence: create stores the declared mode in its own column (single source,
     *  readable without re-running the wizard). */
    @Test
    void create_场景行落隔离模式列() {
        long scenarioId = newScenario(prodEnvId, "落列场景", 30, "NS_ACCOUNT");

        String stored = jdbcTemplate.queryForObject(
                "SELECT isolation_mode FROM tp_perf_scenario WHERE id = ?", String.class, scenarioId);
        assertEquals("NS_ACCOUNT", stored);
    }

    /** Full three-layer production path still launches: switch on + whitelist + approval + mode. */
    @Test
    void launch_生产三层门全齐_放行且快照带模式() {
        restoreSeed("perf.production-enabled", "true");
        addWhitelist(prodEnvId, 100);
        long approvalId = perfApprovalService.apply(projectId, operatorId,
                new PerfApprovalInput(prodEnvId, 100, "SHADOW_TABLE", "容量验收窗口"));
        perfApprovalService.approve(approvalId, operatorId);

        long scenarioId = newScenario(prodEnvId, "全齐生产场景", 80, "SHADOW_TABLE");
        long runId = perfRunService.launch(projectId, scenarioId, null);

        assertEquals(1, ((Number) perfRunService.view(projectId, runId).get("status")).intValue());
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT pressure_snapshot::text FROM tp_perf_run WHERE id = ?",
                String.class, runId);
        assertTrue(snapshot.replaceAll("\\s", "").contains("\"isolationMode\":\"SHADOW_TABLE\""),
                "生产放行快照同样必须钉入申报模式: " + snapshot);
    }
}
