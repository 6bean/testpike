package com.sixbean.testpike.core.perf.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
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
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * GA-2 environment execution lock at the perf launch point.
 * <p>Gap pinned by this class: before the fix, nothing prevented a second perf run
 * from being launched against an environment that already carries an active run,
 * and nothing prevented a perf run from being pressed while a regression batch is
 * executing on the same environment (perf × regression stepping on each other with
 * zero protection). The launch transaction must now consult the occupant first:
 * an active run on the same env (status=1) or an executing batch (status=4 bound to
 * the env) rejects the launch with a human-readable reason that names the occupant
 * and, for an active perf run, its estimated release moment (started_at + declared
 * duration + margin; honest fallback wording when the duration is unresolvable).
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture follows
 * PerfRunLaunchGateTest — config writes go through ConfigService so the cache never
 * keeps a stale seed).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunEnvExclusionTest {

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
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-excl-ex-",
                "GATLING", "K6");
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "环境锁测试项目_" + uniq, "PFEXCL" + uniq);
        devEnvId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-环境锁', 1, 'http://perf-target.local/api')", projectId);
        otherEnvId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '预发-环境锁', 2, 'http://perf-other.local/api')", projectId);
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
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-excl-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFEXCL%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFEXCL%'");
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "env exclusion seed managed by env-lock test"));
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
                        "url", "http://perf-target.local/api/orders")),
                null, null));
    }

    private void seedExecutingBatch(long envId, String name) {
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, "
                        + "status, started_at) VALUES (?, ?, ?, ?, 4, now())",
                projectId, envId, "PFEXCLB" + System.nanoTime() % 1_000_000L, name);
    }

    /** GA-2 red anchor (must be red before the fix): a second launch against an
     *  environment that already carries an active run is rejected, and the reason
     *  names the occupying run, its scenario and the estimated release moment. */
    @Test
    void launch_同环境二连发_第二发点名占用运行与预计释放时刻() {
        addWhitelist(devEnvId, 100);
        long firstScenario = newScenario(devEnvId, "先跑场景", 30, 60);
        long firstRun = perfRunService.launch(projectId, firstScenario, operatorId);

        long secondScenario = newScenario(devEnvId, "二连场景", 20, 60);
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, secondScenario, operatorId));
        assertTrue(e.getMessage().contains("运行中"),
                "拒单必须点名同环境存在运行中的压测: " + e.getMessage());
        assertTrue(e.getMessage().contains("先跑场景"),
                "拒单必须带占用运行的场景名: " + e.getMessage());
        assertTrue(e.getMessage().contains("预计"),
                "拒单必须给预计释放时刻: " + e.getMessage());

        Integer runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
        assertEquals(1, runs, "第二发被拒必须零残留，不得落新运行行");

        // The first run itself must still be visible/active (the guard rejects the
        // second, it never kills the first).
        assertEquals(1, ((Number) perfRunService.view(projectId, firstRun).get("status")).intValue());
    }

    /** Lock release semantics: once the active run reaches a final state, the next
     *  launch against the same environment passes again. */
    @Test
    void launch_占用运行结束后_同环境可再发起() {
        addWhitelist(devEnvId, 100);
        long firstScenario = newScenario(devEnvId, "先跑场景", 30, 60);
        long firstRun = perfRunService.launch(projectId, firstScenario, operatorId);
        perfRunService.finish(projectId, firstRun, 2, null);

        long secondScenario = newScenario(devEnvId, "接力场景", 20, 60);
        long secondRun = perfRunService.launch(projectId, secondScenario, operatorId);

        assertEquals(1, ((Number) perfRunService.view(projectId, secondRun).get("status")).intValue(),
                "前一运行落终态后环境锁必须放行接力发起");
    }

    /** Scenario×environment single-run facet: re-launching the very same scenario
     *  while its previous run is active is rejected (named as the same scenario). */
    @Test
    void launch_同场景二连发_单run闸点名场景() {
        addWhitelist(devEnvId, 100);
        long scenario = newScenario(devEnvId, "重复发起场景", 30, 60);
        perfRunService.launch(projectId, scenario, operatorId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, operatorId));
        assertTrue(e.getMessage().contains("重复发起场景"),
                "同场景二连发的拒单必须点名该场景: " + e.getMessage());
    }

    /** Cross-domain occupant: a regression batch executing on the same environment
     *  blocks the perf launch (perf × regression mutual stepping was unprotected). */
    @Test
    void launch_同环境有执行中回归批次_拒单点名批次() {
        addWhitelist(devEnvId, 100);
        seedExecutingBatch(devEnvId, "回归互踩批次");
        long scenario = newScenario(devEnvId, "被批次占用场景", 30, 60);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, scenario, operatorId));
        assertTrue(e.getMessage().contains("执行中") && e.getMessage().contains("回归互踩批次"),
                "拒单必须点名执行中的批次名: " + e.getMessage());

        Integer runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE project_id = ?", Integer.class, projectId);
        assertEquals(0, runs, "批次占用拒单不得留下运行行");
    }

    /** Negative guard: occupancy on a different environment must not block this one,
     *  and a finished batch (status != 4) never blocks. */
    @Test
    void launch_他环境占用与已结束批次_不误拦() {
        addWhitelist(devEnvId, 100);
        addWhitelist(otherEnvId, 100);
        // active perf run on the other env
        long otherScenario = newScenario(otherEnvId, "他环境占用", 20, 60);
        perfRunService.launch(projectId, otherScenario, operatorId);
        // finished batch bound to our env (status 6 = completed)
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, "
                        + "status, started_at, finished_at) VALUES (?, ?, 'PFEXCLDONE', "
                        + "'已完成批次', 6, now() - interval '1 hour', now())", projectId, devEnvId);

        long scenario = newScenario(devEnvId, "本环境可发场景", 30, 60);
        long runId = perfRunService.launch(projectId, scenario, operatorId);

        assertTrue(runId > 0, "他环境运行与本环境已结束批次都不得拦截发起");
    }

    /** Honest fallback: an occupant whose pinned snapshot carries no resolvable
     *  duration gets an honest wording instead of a fabricated release time. */
    @Test
    void launch_占用快照无时长_诚实文案不编造释放时刻() throws Exception {
        addWhitelist(devEnvId, 100);
        // Legacy-style pinned row: snapshot without a usable duration (model absent).
        Map<String, Object> legacySnapshot = Map.of(
                "name", "历史口径场景",
                "pressureConfig", objectMapper.writeValueAsString(Map.of("targetRps", 10)));
        long scenarioId = newScenario(devEnvId, "历史口径场景", 10, 60);
        // Re-pin the duration away: the occupant row below models a legacy run whose
        // snapshot predates duration persistence.
        jdbcTemplate.update("INSERT INTO tp_perf_run (scenario_id, project_id, status, "
                        + "pressure_snapshot, operator_id, started_at) VALUES (?, ?, 1, "
                        + "CAST(? AS jsonb), ?, now() - interval '3 minutes')",
                scenarioId, projectId, objectMapper.writeValueAsString(legacySnapshot), operatorId);

        long secondScenario = newScenario(devEnvId, "第二发", 20, 60);
        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.launch(projectId, secondScenario, operatorId));
        assertTrue(e.getMessage().contains("无法给出预计释放"),
                "时长不可解析时必须诚实说明，禁编造时刻: " + e.getMessage());
        assertFalseLike(e.getMessage(), "预计释放于",
                "无时长占用者不得凭空报释放时间点");
    }

    private static void assertFalseLike(String message, String forbidden, String why) {
        assertTrue(!message.contains(forbidden), why + ": " + message);
    }
}
