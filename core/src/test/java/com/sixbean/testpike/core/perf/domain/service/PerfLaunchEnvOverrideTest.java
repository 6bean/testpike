package com.sixbean.testpike.core.perf.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
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
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * GB-1 environment override at the perf launch point (scenario × environment decoupling).
 * <p>Before this segment a scenario was welded to its creation-time environment: launching
 * always read {@code tp_perf_scenario.env_id}, so pressing the same journey against a
 * second environment meant duplicating the scenario. The launch call now accepts an
 * optional environment id — when present it decides the safety gate tier (whitelist /
 * approval / isolation), the GA-2 environment lock, the dispatch base-url resolution
 * and the pinned run snapshot; when absent the scenario default applies unchanged
 * (backward-compatible fallback spectrum).
 * <p>Red anchors pinned here: an override launch against an environment that carries no
 * whitelist row must be rejected by the gate (proving the gate consulted the override,
 * not scenario.env_id); the snapshot must record the effective environment; the GA-2
 * lock must block the overridden environment (and leave the scenario default free);
 * a foreign-project override is rejected with a human-readable reason and zero residue;
 * null override keeps the historical behaviour.
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture follows
 * PerfRunEnvExclusionTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfLaunchEnvOverrideTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;
    private long devEnvId;
    private long otherEnvId;
    private long foreignEnvId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-env-override-ex-",
                "GATLING", "K6");
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "环境下沉测试项目_" + uniq, "PFENVO" + uniq);
        devEnvId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-环境A', 1, 'http://perf-a.local/api')", projectId);
        otherEnvId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '测试-环境B', 2, 'http://perf-b.local/api')", projectId);
        long otherProject = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "环境下沉他项目_" + uniq, "PFENVF" + uniq);
        foreignEnvId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '他项目环境', 1, 'http://perf-x.local/api')", otherProject);
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
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-env-override-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFENVO%' OR code LIKE 'PFENVF%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFENVO%' OR code LIKE 'PFENVF%'");
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "env override seed managed by env-sink test"));
    }

    private void addWhitelist(long envId, int maxConcurrency) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, ?, CAST(? AS jsonb))",
                projectId, envId, maxConcurrency, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId, String name, int targetRps, int durationSeconds) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", targetRps, "durationSeconds", durationSeconds),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-a.local/api/orders")),
                null, null));
    }

    private JsonNode snapshotOf(long runId) throws Exception {
        String raw = (String) perfRunService.view(projectId, runId).get("pressure_snapshot");
        return objectMapper.readTree(raw);
    }

    private int runCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
    }

    /** Override spectrum: a scenario welded to env A launched with an explicit env B must
     *  be judged against B (B carries the whitelist, A is deliberately left out), pin B into
     *  the snapshot, and take the GA-2 lock on B — not on A. */
    @Test
    void launch_显式选环境B_闸与锁与快照全按B判定() throws Exception {
        addWhitelist(otherEnvId, 100);
        addWhitelist(devEnvId, 100);
        long scenario = newScenario(devEnvId, "跨环境场景", 30, 60);

        long run = perfRunService.launch(projectId, scenario, otherEnvId, operatorId);

        assertEquals(otherEnvId, snapshotOf(run).path("envId").asLong(),
                "快照必须记 effective envId=所选环境 B: " + snapshotOf(run).toString());

        // GA-2 lock was taken on B: a second override launch onto B is rejected naming
        // the occupant, while A stays free (launch without override passes).
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, otherEnvId, operatorId),
                "覆盖发起占用 B 后，B 上的第二发必须被环境锁拒");
        assertTrue(e.getMessage().contains("跨环境场景"), "拒单必须点名占用运行场景: " + e.getMessage());

        long fallbackRun = perfRunService.launch(projectId, scenario, devEnvId, operatorId);
        assertEquals(devEnvId, snapshotOf(fallbackRun).path("envId").asLong(),
                "A 未被 B 的锁误占用——显式选 A 必须放行");
    }

    /** Gate-honesty anchor: with the whitelist only on A, an explicit override to B
     *  (unwhitelisted) must be rejected by the gate — proving the gate consulted the
     *  override instead of scenario.env_id. Rejection leaves zero run rows. */
    @Test
    void launch_覆盖到未备案环境_闸按B拒单零残留() {
        addWhitelist(devEnvId, 100);
        long scenario = newScenario(devEnvId, "白名单错位场景", 30, 60);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, otherEnvId, operatorId));
        assertTrue(e.getMessage().contains("白名单"),
                "闸按所选环境判定后，未备案的 B 必须被白名单步拒单: " + e.getMessage());
        assertEquals(0, runCount(), "被拒的覆盖发起必须零运行行残留");
    }

    /** Isolation spectrum: an override into another project's environment is rejected
     *  with the ownership wording, never silently falls back to the scenario default. */
    @Test
    void launch_覆盖到他项目环境_点名归属拒单() {
        addWhitelist(devEnvId, 100);
        addWhitelist(foreignEnvId, 100);
        long scenario = newScenario(devEnvId, "越权环境场景", 30, 60);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, foreignEnvId, operatorId));
        assertTrue(e.getMessage().contains("不属于该项目"),
                "他项目环境必须被人话点名拒单: " + e.getMessage());
    }

    /** Fallback spectrum: null override keeps the historical behaviour — the effective
     *  environment is the scenario default and the snapshot records it. */
    @Test
    void launch_缺省不选_回退场景默认环境() throws Exception {
        addWhitelist(devEnvId, 100);
        long scenario = newScenario(devEnvId, "回退场景", 30, 60);

        long run = perfRunService.launch(projectId, scenario, null, operatorId);

        assertEquals(devEnvId, snapshotOf(run).path("envId").asLong(),
                "缺省发起快照记场景默认环境");
        // The three-arg historical entry stays byte-for-byte compatible.
        perfRunService.finish(projectId, run, 2, null);
        long legacy = perfRunService.launch(projectId, scenario, operatorId);
        assertEquals(devEnvId, snapshotOf(legacy).path("envId").asLong(),
                "三参旧入口行为不变（回退谱）");
    }
}
