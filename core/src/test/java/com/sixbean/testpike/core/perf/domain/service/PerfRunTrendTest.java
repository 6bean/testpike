package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfHistogram;
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
 * Scenario trend read face : the whole-run percentile
 * history of one perf scenario, newest-window first, ascending rows.
 * <p>Contract locked by this class:
 * <ul>
 * <li>terminal runs only (normal/breaker/manual stop) — an in-flight run
 * never enters the trend rows;</li>
 * <li>quantiles = merged per-second histogram buckets read at nearest rank
 * (the same math the SLA judge uses, shared merge helper — no second
 * head); legacy frames without buckets fall back to the per-second p95
 * max honestly annotated with the fallback caliber;</li>
 * <li>error rate = sum(fail)/sum(req) weighted when bucket counts exist,
 * else the equal-weight per-second mean (judge family, never diverge);</li>
 * <li>the window is a hot-updatable K8 key (perf.trend.max-runs, default
 * 200) — older runs beyond the window are honestly dropped;</li>
 * <li>foreign scenario = human NOT_FOUND, no existence leak; a scenario
 * without terminal runs = honest empty list.</li>
 * </ul>
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfReportSameDslCompareTest; the GA-2 environment lock admits one
 * in-flight run per env, so seeds ship frames while the run is still running).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunTrendTest {

    @Autowired
    private PerfReportService perfReportService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)", "趋势读数测试项目_" + uniq, "PFTRD" + uniq);
        envId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) " + "VALUES (?, '性能-趋势', 1, 'http://perf-target.local/api')", projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                + "allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFTRD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFTRD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFTRD%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFTRD%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFTRD%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFTRD%'");
        // The trend window key is dev-seeded by migration; tests that overwrite it
        // restore the seed value so the shared cache never carries a dirty window.
        restoreConfigSeed("perf.trend.max-runs", "200");
    }

    /** Config write via ConfigService = cache synchronously invalidated (known lesson). */
    private void restoreConfigSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        if (value != null) {
            configService.create(null, new ConfigInput(key, value, "系统", "int", 0,
                    "perf trend window seed managed by trend read-face tests"));
        }
    }

    private long newScenario(String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    /** Histogram JSON with a single populated bucket (boundary index/count as int pairs). */
    private static String hist(int boundaryIndex, long count) {
        long[] counts = new long[PerfHistogram.boundaries().length];
        counts[boundaryIndex] = count;
        return PerfHistogram.toJson(counts);
    }

    private static PerfMetricFrame bucketFrame(int offset, long req, long fail,
                                               double p95, int histIndex, int histCount) {
        return new PerfMetricFrame(offset, (double) req, p95,
                req == 0 ? 0 : (double) fail / req,
                null, null, null, null, null,
                req, fail, p95 * req, hist(histIndex, histCount), null, "");
    }

    /** Legacy nine-key frame (no buckets — the fallback caliber raw material). */
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

    /** Red anchor 1: merged-bucket quantiles + weighted error rate on the ascending row. */
    @Test
    void terminalRunWithBuckets_readsMergedQuantilesAndWeightedError() {
        long scenarioId = newScenario("分桶趋势场景");
        // 100 samples in the <=100ms bucket + 100 in the <=200ms bucket:
        // N=200 → p50 rank 100 lands on 100ms, p95 rank 190 and p99 rank 198 land on 200ms.
        long runId = launchSeedAndFinish(scenarioId, List.of(
                bucketFrame(0, 100, 5, 100.0, 9, 100),
                bucketFrame(1, 100, 15, 200.0, 13, 100)));
        perfRunService.mark(projectId, runId, "GOLDEN", null);

        List<Map<String, Object>> rows = perfReportService.trend(projectId, scenarioId);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("runId")).longValue()).isEqualTo(runId);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(row.get("mark"))).isEqualTo("GOLDEN");
        assertThat(((Number) row.get("p50Ms")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) row.get("p95Ms")).doubleValue()).isEqualTo(200.0);
        assertThat(((Number) row.get("p99Ms")).doubleValue()).isEqualTo(200.0);
        assertThat((String) row.get("quantileCaliber")).isEqualTo("BUCKET");
        // weighted error rate = (5+15)/(100+100) = 0.10, not the equal-weight 0.10 mean here
        assertThat(((Number) row.get("errorRate")).doubleValue()).isCloseTo(0.10,
                org.assertj.core.api.Assertions.within(1e-9));
        assertThat((String) row.get("errorCaliber")).isEqualTo("WEIGHTED");
        assertThat(((Number) row.get("totalReq")).longValue()).isEqualTo(200L);
        assertThat(((Number) row.get("sampleSeconds")).intValue()).isEqualTo(2);
    }

    /** Red anchor 2: legacy frames degrade honestly — p95 max fallback, no fake p50/p99. */
    @Test
    void legacyFramesWithoutBuckets_fallBackToPerSecondP95MaxAnnotated() {
        long scenarioId = newScenario("回退口径场景");
        long runId = launchSeedAndFinish(scenarioId, List.of(
                legacyFrame(0, 80.0, 120.0, 0.02),
                legacyFrame(1, 70.0, 90.0, 0.04)));

        List<Map<String, Object>> rows = perfReportService.trend(projectId, scenarioId);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("runId")).isNotNull();
        assertThat(((Number) row.get("p95Ms")).doubleValue()).isEqualTo(120.0);
        assertThat(row.get("p50Ms")).as("no buckets = p50 never fabricated").isNull();
        assertThat(row.get("p99Ms")).as("no buckets = p99 never fabricated").isNull();
        assertThat((String) row.get("quantileCaliber")).isEqualTo("FALLBACK");
        // equal-weight mean of the per-second error rates = (0.02+0.04)/2
        assertThat(((Number) row.get("errorRate")).doubleValue()).isCloseTo(0.03,
                org.assertj.core.api.Assertions.within(1e-9));
        assertThat((String) row.get("errorCaliber")).isEqualTo("MEAN");
        assertThat(((Number) row.get("totalReq")).longValue()).isZero();
    }

    /** Red anchor 3: multiple terminal runs come back ascending by id; in-flight stays out. */
    @Test
    void multipleRuns_ascendingRowsAndInFlightExcluded() {
        long scenarioId = newScenario("多运行趋势场景");
        long runA = launchSeedAndFinish(scenarioId, List.of(legacyFrame(0, 50.0, 110.0, 0.0)));
        long runB = launchSeedAndFinish(scenarioId, List.of(legacyFrame(0, 50.0, 130.0, 0.0)));
        // GA-2 lock: this third launch stays in flight (no finish) until the test ends —
        // env rows are hard-deleted in cleanup, which releases the lock again.
        long runC = perfRunService.launch(projectId, scenarioId, null);

        List<Map<String, Object>> rows = perfReportService.trend(projectId, scenarioId);

        assertThat(rows).extracting(r -> ((Number) r.get("runId")).longValue())
                .containsExactly(runA, runB);
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("status")).intValue())
                .isIn(2, 3, 4));
    }

    /** Red anchor 4: window key truncates to the newest N, rows stay ascending. */
    @Test
    void windowKeyBeyondRunCount_keepsNewestWindowAscending() {
        restoreConfigSeed("perf.trend.max-runs", "3");
        long scenarioId = newScenario("窗口截断场景");
        long[] runs = new long[5];
        for (int i = 0; i < 5; i++) {
            runs[i] = launchSeedAndFinish(scenarioId, List.of(legacyFrame(0, 40.0, 100.0, 0.0)));
        }

        List<Map<String, Object>> rows = perfReportService.trend(projectId, scenarioId);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> ((Number) r.get("runId")).longValue())
                .containsExactly(runs[2], runs[3], runs[4]);
    }

    /** Red anchor 5: honest empty for a scenario without any terminal run. */
    @Test
    void scenarioWithoutTerminalRuns_returnsEmptyHonestly() {
        long scenarioId = newScenario("空历史场景");

        assertThat(perfReportService.trend(projectId, scenarioId)).isEmpty();
    }

    /** Red anchor 6: foreign project = NOT_FOUND human wording, existence never leaked. */
    @Test
    void foreignProject_rejectedWithHumanNotFound() {
        long scenarioId = newScenario("隔离趋势场景");
        long otherProject = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(id), 0) + 999999 FROM tp_project", Long.class);

        assertThatThrownBy(() -> perfReportService.trend(otherProject, scenarioId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("压测场景不存在或不属于该项目");
    }
}
