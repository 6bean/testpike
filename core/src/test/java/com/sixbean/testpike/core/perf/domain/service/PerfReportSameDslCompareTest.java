package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Cross-scenario comparison relaxed by the pinned DSL fingerprint .
 * <p>Gap pinned by this class: the compare gate rejects whenever scenario ids
 * differ, so two scenarios carrying byte-identical DSL (same pressure model, same
 * config, same steps, same effective SLA) can never have their curves compared —
 * the exact situation of "scenario cloned for a new environment, compare the load
 * behaviour". The relaxed contract locked here:
 * <ul>
 * <li>launch freezes a sha256 fingerprint of the stable DSL serialization into
 * the pinned snapshot under the key dslHash (single write position, later
 * scenario edits never rewrite it);</li>
 * <li>compare admits a pair when the scenarios match OR both snapshots carry the
 * same dslHash;</li>
 * <li>legacy runs without a fingerprint fall back to the same-scenario gate
 * semantics — the missing value is never guessed into an equality;</li>
 * <li>different scenarios with different DSLs keep rejecting in human wording
 * naming both bases.</li>
 * </ul>
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfReportCompareTest; the GA-2 environment lock forces runs to finish
 * before the next launch on the same env).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportSameDslCompareTest {

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-dsl-ex-",
                "GATLING", "K6");
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "同DSL对比测试项目_" + uniq, "PFDSL" + uniq);
        envId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '性能-同DSL', 1, 'http://perf-target.local/api')", projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-dsl-ex-");
        // Fixture-scoped teardown through the shared sweep (R40): the FK tree
        // reaches tp_perf_* / tp_project_env AND tp_exec_task — the hand-written
        // DELETE list below used to omit tp_exec_task, leaking pending task rows
        // that later suites' claim walks picked up (seen in fresh-install CI triage).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PFDSL%'", Long.class));
    }


    /** Scenario carrying the canonical DSL (name differs per call, DSL stays identical). */
    private long newScenarioWithDsl(String name, int targetRps) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", targetRps, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    private static PerfMetricFrame frame(int offset, double rps, double p95, double err) {
        return new PerfMetricFrame(offset, rps, p95, err, null, null, null, null, null);
    }

    /** GA-2 lock admits one in-flight load per env: seed frames while the run is
     *  still running (finished runs reject late frames), then release the env. */
    private long launchSeedAndFinish(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId, frames);
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");
        return runId;
    }

    @Autowired
    private PerfMetricService perfMetricService;

    private String dslHashOf(long runId) {
        Object snapshot = perfRunService.view(projectId, runId).get("pressure_snapshot");
        try {
            Map<String, Object> parsed = objectMapper.readValue(String.valueOf(snapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object hash = parsed.get("dslHash");
            return hash == null ? null : String.valueOf(hash);
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** Red anchor 1: launch freezes a 64-hex sha256 fingerprint into the pinned snapshot. */
    @Test
    void launch_pinsDslFingerprintIntoSnapshot() {
        long runId = launchSeedAndFinish(newScenarioWithDsl("指纹钉版场景", 50),
                List.of(frame(0, 40.0, 120.0, 0.0)));

        String hash = dslHashOf(runId);
        assertThat(hash).as("运行钉版快照必须携带 dslHash").matches("[0-9a-f]{64}");
    }

    /** Red anchor 2: two scenarios with identical DSL share the same fingerprint. */
    @Test
    void identicalDslAcrossScenarios_producesSameFingerprint() {
        long runA = launchSeedAndFinish(newScenarioWithDsl("克隆甲", 50),
                List.of(frame(0, 40.0, 120.0, 0.0)));
        long runB = launchSeedAndFinish(newScenarioWithDsl("克隆乙", 50),
                List.of(frame(0, 40.0, 120.0, 0.0)));

        assertThat(dslHashOf(runA)).isEqualTo(dslHashOf(runB));
    }

    /** The capability itself: same-DSL cross-scenario pair becomes comparable. */
    @Test
    void sameDslFingerprintAcrossScenarios_isComparable() {
        long runA = launchSeedAndFinish(newScenarioWithDsl("可对比甲", 50),
                List.of(frame(0, 40.0, 120.0, 0.02), frame(1, 50.0, 150.0, 0.04)));
        long runB = launchSeedAndFinish(newScenarioWithDsl("可对比乙", 50),
                List.of(frame(0, 30.0, 100.0, 0.01)));

        Map<String, Object> compare = perfReportService.compare(projectId, runA, runB);

        // Diff table keeps the historical contract (A minus B over shared seconds).
        assertThat((List<?>) compare.get("diffs")).hasSize(1);
        Map<String, Object> d0 = (Map<String, Object>) ((List<?>) compare.get("diffs")).get(0);
        assertThat(((Number) d0.get("rpsDelta")).doubleValue()).isCloseTo(10.0,
                org.assertj.core.api.Assertions.within(1e-9));
    }

    /** Different DSL across scenarios still rejects, wording naming both bases. */
    @Test
    void differentDslAcrossScenarios_rejectedInHumanWording() {
        long runA = launchSeedAndFinish(newScenarioWithDsl("差异甲", 50),
                List.of(frame(0, 40.0, 120.0, 0.0)));
        long runB = launchSeedAndFinish(newScenarioWithDsl("差异乙", 80),
                List.of(frame(0, 30.0, 100.0, 0.0)));

        assertThatThrownBy(() -> perfReportService.compare(projectId, runA, runB))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非同一场景")
                .satisfies(e -> assertThat(((ServiceException) e).getCode())
                        .isEqualTo(BizErrorCode.VALIDATION_ERROR));
    }

    /** Legacy fallback: runs whose snapshot carries no fingerprint never gain
     *  cross-scenario comparability (missing value is never guessed equal). */
    @Test
    void legacyRunsWithoutFingerprint_fallBackToSameScenarioGate() {
        long scenarioA = newScenarioWithDsl("存量甲", 50);
        long scenarioB = newScenarioWithDsl("存量乙", 50);
        long legacyA = insertLegacyRun(scenarioA);
        long legacyB = insertLegacyRun(scenarioB);

        assertThatThrownBy(() -> perfReportService.compare(projectId, legacyA, legacyB))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非同一场景");

        // Same scenario with legacy snapshots stays comparable (historical gate semantics).
        long legacySameScenario = insertLegacyRun(scenarioA);
        Map<String, Object> compare = perfReportService.compare(projectId, legacyA, legacySameScenario);
        assertThat(compare.get("runA")).isNotNull();
    }

    /** Insert a finished run whose pinned snapshot predates the fingerprint feature. */
    private long insertLegacyRun(long scenarioId) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_perf_run (scenario_id, project_id, "
                        + "status, pressure_snapshot, finished_at) VALUES (?, ?, 2, "
                        + "CAST('{\"name\":\"legacy\"}' AS jsonb), now()) RETURNING id",
                Long.class, scenarioId, projectId);
    }
}
