package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * paged run window behind the workbench run drawer.
 * <p>The drawer used to pull every run of a scenario in one unbounded list —
 * a scenario with 200+ runs flooded the panel and the endpoint carried no
 * page/pageSize (S-2 contract debt). The window keeps the drawer's historical
 * newest-first id DESC order (a headerless div list has no sortable columns,
 * so the sort whitelist is exempted here with that reason, not silently),
 * echoes a truthful total for the load-more affordance, clamps nonsense
 * windows, and answers a ghost scenario with an honest empty window instead
 * of an error page (same posture as the scenario window from .
 * <p>Real chain, no mocks: rows seeded over the live scenario service and the
 * dev database, assertions read the served window back.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunPagedWindowTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "运行窗口测试项目_" + uniq, "PERFWINDOW" + uniq);
        envId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '性能-运行窗口', 1, 'http://perf-target.local/api')", projectId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFWINDOW%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFWINDOW%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFWINDOW%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFWINDOW%'");
    }

    private long newScenario(String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    /** Seed a finished run row directly (the drawer reads rows, not the launch chain). */
    private long seedRun(long scenarioId) {
        Long id = jdbcTemplate.queryForObject("INSERT INTO tp_perf_run "
                        + "(scenario_id, project_id, status, pressure_snapshot, stop_reason) "
                        + "VALUES (?, ?, 2, CAST(? AS jsonb), ?) RETURNING id",
                new Object[]{scenarioId, projectId, "{\"name\":\"window-seed\"}", "正常结束"},
                Long.class);
        return id == null ? 0L : id;
    }

    @Test
    void pagedWindowContinuesNewestFirstAndEchoesTruthfulTotal() {
        long scenarioId = newScenario("窗口主场景");
        long run1 = seedRun(scenarioId);
        long run2 = seedRun(scenarioId);
        long run3 = seedRun(scenarioId);

        var first = perfRunService.listByScenarioPaged(projectId, scenarioId, 1, 2);
        assertThat(first.total()).as("total counts every run of the scenario").isEqualTo(3);
        assertThat(first.list()).as("the window carries only the page").hasSize(2);
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.pageSize()).isEqualTo(2);
        assertThat((Number) first.list().get(0).get("id")).as("newest first").isEqualTo(run3);
        assertThat((Number) first.list().get(1).get("id")).isEqualTo(run2);

        var second = perfRunService.listByScenarioPaged(projectId, scenarioId, 2, 2);
        assertThat((Number) second.list().get(0).get("id")).as("page 2 continues the order").isEqualTo(run1);
        assertThat(second.total()).isEqualTo(3);
    }

    @Test
    void windowStaysScopedToTheAskedScenario() {
        long scenarioA = newScenario("窗口场景甲");
        long scenarioB = newScenario("窗口场景乙");
        seedRun(scenarioA);
        seedRun(scenarioA);
        seedRun(scenarioB);

        var window = perfRunService.listByScenarioPaged(projectId, scenarioA, 1, 20);
        assertThat(window.total()).as("sibling scenario rows never leak in").isEqualTo(2);
        assertThat(window.list()).allSatisfy(row ->
                assertThat(((Number) row.get("scenario_id")).longValue()).isEqualTo(scenarioA));
    }

    @Test
    void nonsenseWindowIsClampedAndGhostScenarioAnswersHonestZero() {
        long scenarioId = newScenario("窗口边界场景");
        seedRun(scenarioId);

        var clamped = perfRunService.listByScenarioPaged(projectId, scenarioId, 0, 99999);
        assertThat(clamped.page()).as("page below 1 clamps to 1").isEqualTo(1);
        assertThat(clamped.pageSize())
                .as("oversized window clamps to the shared cap").isEqualTo(500);
        assertThat(clamped.total()).isEqualTo(1);

        var ghost = perfRunService.listByScenarioPaged(projectId, 999_999_999L, 1, 20);
        assertThat(ghost.total()).as("ghost scenario answers a truthful zero, not an error").isZero();
        assertThat(ghost.list()).isEmpty();
    }

    /**
     * The windowed door must serve the exact same row shape the unbounded
     * drawer list has today (snake_case view columns), so the frontend run-row
     * fields stay untouched — only the transport gains a window.
     */
    @Test
    void windowRowsCarryTheHistoricalViewColumns() {
        long scenarioId = newScenario("窗口列形场景");
        long runId = seedRun(scenarioId);

        var window = perfRunService.listByScenarioPaged(projectId, scenarioId, 1, 20);
        Map<String, Object> row = window.list().get(0);
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(runId);
        assertThat(row).containsKeys("scenario_id", "project_id", "status",
                "pressure_snapshot", "stop_reason", "operator_id",
                "started_at", "finished_at", "created_at");
    }
}
