package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
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

/**
 * CI gate verdict read face for perf runs : a CI pipeline
 * asks "does this run pass the SLA gates" and gets the same single-source verdict
 * the report shows — never a second judge head.
 * <p>Contract locked by this class:
 * <ul>
 * <li>terminal non-VOID run → verdict pass/fail with breachedGates passed
 * through from the SLA three-gate judge and the mark echoed for the
 * pipeline badge;</li>
 * <li>in-flight run → pending with the current status named in human wording
 * (no hard judgment on partial data);</li>
 * <li>VOID-marked terminal run → pending naming the void as the reason —
 * a voided run is never a gate basis;</li>
 * <li>foreign/unknown run → NOT_FOUND in human wording, existence never
 * leaked (ownership rides {@code PerfRunService.view} single point).</li>
 * </ul>
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfRunTrendTest; the GA-2 environment lock admits one in-flight run
 * per env, so seeds ship frames while finishing to release it).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunCiVerdictTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-perf-civer-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6");

        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "CI门禁读数测试项目_" + uniq, "PFVCD" + uniq);
        envId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '性能-CI', 1, 'http://perf-target.local/api')", projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    
        if (fleetSeat != null) {
            fleetSeat.close();
            fleetSeat = null;
        }
}

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFVCD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFVCD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFVCD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFVCD%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFVCD%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFVCD%'");
    }

    /** Scenario with explicit SLA thresholds pinned into the launch snapshot. */
    private long newScenarioWithSla(String name, Map<String, Double> sla) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), sla));
    }

    /** Legacy nine-key frame (no buckets — judge falls back to per-second readings). */
    private static PerfMetricFrame legacyFrame(int offset, double rps, double p95, double err) {
        return new PerfMetricFrame(offset, rps, p95, err, null, null, null, null, null);
    }

    /** GA-2 lock admits one in-flight load per env: seed then finish to release it. */
    private long launchSeedAndFinish(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId, frames);
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");
        return runId;
    }

    /** Red anchor 1: terminal run under SLA limits → pass, gates empty, mark echoed. */
    @Test
    void terminalRunWithinSla_readsPassVerdictWithMarkEcho() {
        long scenarioId = newScenarioWithSla("门禁通过场景", Map.of("p95Ms", 500.0, "errorRate", 0.05));
        long runId = launchSeedAndFinish(scenarioId, List.of(
                legacyFrame(0, 80.0, 120.0, 0.01),
                legacyFrame(1, 70.0, 90.0, 0.02)));
        perfRunService.mark(projectId, runId, "GOLDEN", null);

        Map<String, Object> verdict = perfReportService.ciVerdict(projectId, runId);

        assertThat(((Number) verdict.get("runId")).longValue()).isEqualTo(runId);
        assertThat((String) verdict.get("verdict")).isEqualTo("pass");
        assertThat((String) verdict.get("mark")).isEqualTo("GOLDEN");
        assertThat((List<?>) verdict.get("breachedGates")).isEmpty();
        assertThat(String.valueOf(verdict.get("verdictPhrase"))).contains("SLA 三门禁全部通过");
    }

    /** Red anchor 2: terminal run breaching the P95 gate → fail naming breached gates. */
    @Test
    void terminalRunBreachingSla_readsFailWithBreachedGates() {
        long scenarioId = newScenarioWithSla("门禁越线场景", Map.of("p95Ms", 50.0));
        long runId = launchSeedAndFinish(scenarioId, List.of(
                legacyFrame(0, 80.0, 120.0, 0.0)));

        Map<String, Object> verdict = perfReportService.ciVerdict(projectId, runId);

        assertThat((String) verdict.get("verdict")).isEqualTo("fail");
        assertThat((List<?>) verdict.get("breachedGates")).isNotEmpty();
        assertThat(String.valueOf(verdict.get("verdictPhrase"))).contains("SLA 未通过");
    }

    /** Red anchor 3: in-flight run → pending with the current status named, no hard judge. */
    @Test
    void inFlightRun_readsPendingNamingStatus() {
        long scenarioId = newScenarioWithSla("在跑门禁场景", Map.of("p95Ms", 500.0));
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId,
                List.of(legacyFrame(0, 80.0, 120.0, 0.0)));

        Map<String, Object> verdict = perfReportService.ciVerdict(projectId, runId);

        assertThat((String) verdict.get("verdict")).isEqualTo("pending");
        assertThat((List<?>) verdict.get("breachedGates")).isEmpty();
        assertThat(String.valueOf(verdict.get("verdictPhrase"))).contains("尚未结束");
    }

    /** Red anchor 4: VOID-marked terminal run → pending, the void named as the reason. */
    @Test
    void voidMarkedRun_readsPendingNamingVoidReason() {
        long scenarioId = newScenarioWithSla("作废门禁场景", Map.of("p95Ms", 500.0));
        long runId = launchSeedAndFinish(scenarioId, List.of(
                legacyFrame(0, 80.0, 120.0, 0.0)));
        perfRunService.mark(projectId, runId, "VOID", null);

        Map<String, Object> verdict = perfReportService.ciVerdict(projectId, runId);

        assertThat((String) verdict.get("verdict")).isEqualTo("pending");
        assertThat(String.valueOf(verdict.get("verdictPhrase"))).contains("作废");
    }

    /** Red anchor 5: foreign project = NOT_FOUND human wording, existence never leaked. */
    @Test
    void foreignProject_rejectedWithHumanNotFound() {
        long scenarioId = newScenarioWithSla("隔离门禁场景", Map.of("p95Ms", 500.0));
        long runId = launchSeedAndFinish(scenarioId, List.of(
                legacyFrame(0, 80.0, 120.0, 0.0)));
        // Ghost band verified empty on the live dev db; literal avoids the
        // global project max read that leaked high-id fixtures used to skew.
        long otherProject = 999_999_999L;

        assertThatThrownBy(() -> perfReportService.ciVerdict(otherProject, runId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在或不属于该项目");
    }
}
