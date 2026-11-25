package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * GB-2: the project-wide run ledger window behind the load-test run
 * ledger page.
 * <p>Every existing run door is keyed by scenario, so a run whose scenario was
 * deleted (A-13 keeps finished history with a NULL link) disappears from the
 * workbench entirely even though its id is still reachable — the ledger's job
 * is to list every run of the project segment, decoupled ones included. The
 * window carries page/pageSize (S-2 contract posture, same PageWindow clamp as
 * the scenario and drawer windows), a truthful total, a whitelisted sort so
 * the ledger's sortable headers can actually order, and the scenario name read
 * off the pinned launch snapshot rather than the live scenario row — so a
 * decoupled run still shows the name it ran under instead of a blank.
 * <p>Real chain, no mocks: rows seeded over the live scenario service and the
 * dev database, assertions read the served window back.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunProjectLedgerTest {

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
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-ledger-ex-",
                "GATLING", "K6");
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "运行台账测试项目_", "PERFLEDGER", "性能-运行台账", 1,
                "http://perf-target.local/api", 0, null);
        projectId = seeded[0];
        envId = seeded[1];
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-ledger-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFLEDGER%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFLEDGER%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFLEDGER%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFLEDGER%'");
    }

    private long newScenario(String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 60), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    /** Seed a finished run row directly (the ledger reads rows, not the launch chain). */
    private long seedRun(Long scenarioId, String snapshotName) {
        Long id = jdbcTemplate.queryForObject("INSERT INTO tp_perf_run "
                        + "(scenario_id, project_id, status, pressure_snapshot, stop_reason) "
                        + "VALUES (?, ?, 2, CAST(? AS jsonb), ?) RETURNING id",
                new Object[]{scenarioId, projectId,
                        "{\"name\":\"" + snapshotName + "\",\"pressureModel\":\"CONSTANT_RPS\"}",
                        "正常结束"},
                Long.class);
        return id == null ? 0L : id;
    }

    @Test
    void ledgerListsEveryRunOfTheProjectNewestFirst() {
        long scenarioA = newScenario("台账场景甲");
        long scenarioB = newScenario("台账场景乙");
        long run1 = seedRun(scenarioA, "台账场景甲");
        long run2 = seedRun(scenarioB, "台账场景乙");
        long run3 = seedRun(scenarioA, "台账场景甲");

        var window = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, null);
        assertThat(window.total()).as("every run of the project counts, both scenarios").isEqualTo(3);
        assertThat(window.list()).hasSize(3);
        assertThat((Number) window.list().get(0).get("id")).as("newest first").isEqualTo(run3);
        assertThat((Number) window.list().get(2).get("id")).isEqualTo(run1);
    }

    @Test
    void ledgerKeepsRunsWhoseScenarioWasDeleted() {
        long scenario = newScenario("台账将被删的场景");
        long keptRun = seedRun(scenario, "台账将被删的场景");
        // A-13 decoupling shape: history stays with a NULL link after the recipe goes.
        jdbcTemplate.update("UPDATE tp_perf_run SET scenario_id = NULL WHERE id = ?", keptRun);
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE id = ?", scenario);

        var window = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, null);
        assertThat(window.total()).as("a decoupled run stays on the ledger").isEqualTo(1);
        Map<String, Object> row = window.list().get(0);
        assertThat(row.get("scenario_id")).as("the link is honestly null").isNull();
        assertThat(row.get("scenario_name"))
                .as("the name comes off the pinned snapshot, never a blank")
                .isEqualTo("台账将被删的场景");
    }

    @Test
    void ledgerWindowIsClampedAndForeignProjectNeverLeaks() {
        long scenario = newScenario("台账边界场景");
        seedRun(scenario, "台账边界场景");

        var clamped = perfRunService.listProjectPaged(projectId, 0, 99999, null, null, null, null);
        assertThat(clamped.page()).as("page below 1 clamps to 1").isEqualTo(1);
        assertThat(clamped.pageSize()).as("oversized window clamps to the shared cap").isEqualTo(500);

        long uniq = System.nanoTime() % 1_000_000_000L;
        long otherProject = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "台账他项目_" + uniq, "PLGOTHER" + uniq);
        var foreign = perfRunService.listProjectPaged(otherProject, 1, 20, null, null, null, null);
        assertThat(foreign.total()).as("another project's slice stays empty").isZero();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProject);
    }

    @Test
    void ledgerSortsByWhitelistedColumnsAndRefusesInjections() {
        long scenario = newScenario("台账排序场景");
        long older = seedRun(scenario, "台账排序场景");
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 3, stop_reason = '熔断停止' WHERE id = ?", older);
        long newer = seedRun(scenario, "台账排序场景");

        var byStatus = perfRunService.listProjectPaged(projectId, 1, 20, "status", "asc", null, null);
        assertThat((Number) byStatus.list().get(0).get("id"))
                .as("status asc puts the circuit-broken run first").isEqualTo(newer);
        assertThat(byStatus.total()).isEqualTo(2);

        assertThatThrownBy(() -> perfRunService.listProjectPaged(projectId, 1, 20, "id; DROP TABLE", null, null, null))
                .as("an injection-shaped sort key is refused before any SQL is built")
                .isInstanceOf(com.sixbean.testpike.common.exception.ServiceException.class);
    }

    @Test
    void ledgerFiltersRunsByStatusAndMark() {
        long scenario = newScenario("台账筛选场景");
        long goldenRun = seedRun(scenario, "台账筛选场景");
        long brokenRun = seedRun(scenario, "台账筛选场景");
        jdbcTemplate.update("UPDATE tp_perf_run SET status = 3 WHERE id = ?", brokenRun);
        jdbcTemplate.update("UPDATE tp_perf_run SET mark = 'GOLDEN' WHERE id = ?", goldenRun);

        var byStatus = perfRunService.listProjectPaged(projectId, 1, 20, null, null, 3, null);
        assertThat(byStatus.total()).as("the status filter keeps only the circuit-broken run").isEqualTo(1);
        assertThat((Number) byStatus.list().get(0).get("id")).isEqualTo(brokenRun);

        var byMark = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, "GOLDEN");
        assertThat(byMark.total()).as("the mark filter keeps only the golden run").isEqualTo(1);
        assertThat((Number) byMark.list().get(0).get("id")).isEqualTo(goldenRun);

        var combo = perfRunService.listProjectPaged(projectId, 1, 20, null, null, 2, "GOLDEN");
        assertThat(combo.total()).as("both filters narrow to the intersection").isEqualTo(1);

        var contradiction = perfRunService.listProjectPaged(projectId, 1, 20, null, null, 3, "GOLDEN");
        assertThat(contradiction.total()).as("contradictory filters honestly return an empty window")
                .isZero();
    }

    @Test
    void ledgerFilterTreatsBlankAsNoFilterAndUnknownMarkAsHonestEmpty() {
        long scenario = newScenario("台账筛选空值场景");
        seedRun(scenario, "台账筛选空值场景");

        var blanks = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, "  ");
        assertThat(blanks.total()).as("a blank mark stays the unfiltered window").isEqualTo(1);

        var unknown = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, "NOT_A_MARK");
        assertThat(unknown.total()).as("an unknown mark matches nothing instead of silently widening").isZero();
    }

    @Test
    void ledgerRowsCarryTheColumnsTheTableRenders() {
        long scenario = newScenario("台账列形场景");
        long runId = seedRun(scenario, "台账列形场景");

        var window = perfRunService.listProjectPaged(projectId, 1, 20, null, null, null, null);
        Map<String, Object> row = window.list().get(0);
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(runId);
        assertThat(row).containsKeys("scenario_id", "scenario_name", "project_id", "status",
                "pressure_snapshot", "stop_reason", "operator_id",
                "started_at", "finished_at", "created_at", "mark");
    }
}
