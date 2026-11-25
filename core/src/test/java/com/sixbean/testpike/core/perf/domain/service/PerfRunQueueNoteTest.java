package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queue transparency on the perf run read faces ( segment: a launched run whose
 * dispatch task has not been taken up yet used to read as plain "running" — the
 * operator could not tell pressing-in-progress from waiting-for-a-machine, nor how
 * many perf tasks sit ahead of it). The run view and both scenario run lists now
 * carry a human-readable queue_note for status=1 runs only:
 * <p>— own dispatch task claimed (any non-pending state) or run already terminal
 * = null (nothing to wait for);
 * <p>— task pending with N perf tasks ahead (same FIFO dispatch queue,
 * task_type=5, id order = claim order) = wording names the ahead count;
 * <p>— task pending with nothing ahead = wording tells the machine-availability
 * situation (online set judged by the single ExecutorService source, never a
 * re-implementation here).
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfLaunchEnvOverrideTest, which launches the same two-environment
 * shape used here).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunQueueNoteTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-perf-queue-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envAId;
    private long envBId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6");

        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "排队透明测试项目_" + uniq, "PFQNSO" + uniq);
        envAId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '开发-环境A', 1, 'http://perf-a.local/api')", projectId);
        envBId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '测试-环境B', 2, 'http://perf-b.local/api')", projectId);
        addWhitelist(envAId);
        addWhitelist(envBId);
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    
        if (fleetSeat != null) {
            fleetSeat.close();
            fleetSeat = null;
        }
}

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFQNSO%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFQNSO%'");
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "queue-note test managed seed"));
    }

    private void addWhitelist(long envId) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, 100, CAST(? AS jsonb))",
                projectId, envId, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId, String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-a.local/api/orders")),
                null, null));
    }

    /** A freshly launched run whose dispatch task is still pending must read a
     *  waiting wording on view() — never a bare "running" with no explanation. */
    @Test
    void 刚发起未领取的运行_view带排队人话() {
        long scenario = newScenario(envAId, "队首场景");
        long run = perfRunService.launch(projectId, scenario, operatorId);

        Object note = perfRunService.view(projectId, run).get("queue_note");
        assertNotNull(note, "状态=运行中(1)且派发任务未领取时 view 必须给出排队人话行");
        assertTrue(String.valueOf(note).startsWith("任务排队中"),
                "队首等待谱统一以「任务排队中」开头: " + note);
    }

    /** Two pending perf tasks in the shared FIFO queue: the second launch sees
     *  exactly one more task ahead than the first (the dispatch queue is global —
     *  claim consumes task_type=5 candidates in id order across projects — so the
     *  absolute ahead count rides whatever else is queued, and the assertion pins
     *  the relative increment plus a pre-launch baseline instead of assuming an
     *  empty dev queue). */
    @Test
    void 第二个排队运行的前方数比队首多一() {
        long base = pendingPerfTasks();
        long scenarioA = newScenario(envAId, "前方场景");
        long runA = perfRunService.launch(projectId, scenarioA, operatorId);
        long scenarioB = newScenario(envBId, "后方场景");
        long runB = perfRunService.launch(projectId, scenarioB, operatorId);

        long aheadA = aheadOf(perfRunService.view(projectId, runA).get("queue_note"));
        long aheadB = aheadOf(perfRunService.view(projectId, runB).get("queue_note"));
        assertEquals(base, aheadA, "队首运行的前方数=发起前在册排队数");
        assertEquals(base + 1, aheadB,
                "后发起的运行必须比前一个多 1 个前方任务: " + aheadA + " vs " + aheadB);
    }

    private long pendingPerfTasks() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE task_type = 5 AND status = 1",
                Long.class);
    }

    /** Parses the ahead count out of the queue wording (the "N tasks ahead"
     *  clause), or 0 when the note carries no ahead clause (queue head). */
    private static long aheadOf(Object note) {
        assertTrue(note != null, "排队中的运行必须有排队人话行");
        String text = String.valueOf(note);
        int from = text.indexOf("前方有 ");
        if (from < 0) {
            return 0;
        }
        int to = text.indexOf(' ', from + 4);
        return Long.parseLong(text.substring(from + 4, to));
    }

    /** Terminal runs have nothing to wait for — the queue note must be absent
     *  (null) on view, and the paged list only fills it for status=1 rows. */
    @Test
    void 终态运行不带排队行且列表只给在跑行填() {
        long scenario = newScenario(envAId, "将结束场景");
        long run = perfRunService.launch(projectId, scenario, operatorId);
        perfRunService.finish(projectId, run, 2, null);

        assertNull(perfRunService.view(projectId, run).get("queue_note"),
                "终态运行不得再有排队人话行");

        // Relaunch a waiting run of the same scenario and read it through the
        // paged list face — the row must carry the note.
        long run2 = perfRunService.launch(projectId, scenario, operatorId);
        List<Map<String, Object>> rows = perfRunService
                .listByScenarioPaged(projectId, scenario, 1, 20).list();
        Map<String, Object> waiting = rows.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == run2)
                .findFirst().orElseThrow();
        assertNotNull(waiting.get("queue_note"), "分页列表对在跑(未领取)行必须带排队人话行");
        Map<String, Object> done = rows.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == run)
                .findFirst().orElseThrow();
        assertNull(done.get("queue_note"), "分页列表对终态行必须为 null");
        // The plain list face rides the same fill (shared single point).
        List<Map<String, Object>> plain = perfRunService.listByScenario(projectId, scenario);
        assertEquals(2, plain.size());
        assertNotNull(plain.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == run2)
                .findFirst().orElseThrow().get("queue_note"));
    }
}
