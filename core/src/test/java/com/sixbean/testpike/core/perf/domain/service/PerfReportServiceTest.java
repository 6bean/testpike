package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
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


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportServiceTest {

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
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测报告测试项目_" + uniq, "PERFREP" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '性能-报告测', 1, 'http://perf-target.local/api')", projectId);
        envId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFREP%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFREP%'");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " ORDER BY id DESC LIMIT 1",
                Long.class);
    }

    private long newScenario(Integer driverLimit, Map<String, Double> sla) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "报告契约场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, driverLimit,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), sla));
    }

    private long launchAndSeedFrames(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId, frames);
        return runId;
    }

    /** Two-run comparison fixture: the GA-2 environment execution lock admits only one
     *  in-flight load per environment, so the first run is seeded then released (finish =
     *  terminal state; report building is unaffected) before the second launch proceeds. */
    private long launchSeedAndRelease(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = launchAndSeedFrames(scenarioId, frames);
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");
        return runId;
    }

    
    private static List<PerfMetricFrame> framesWithAuth() {
        return List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, 35.0, 4.0),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null));
    }

    @Test
    void metricCardAggregatesThreeLinesAndAuthSeparately() {
        long scenarioId = newScenario(null, null);
        long runId = launchAndSeedFrames(scenarioId, framesWithAuth());
        Map<String, Object> report = perfReportService.build(projectId, runId);

        Map<String, Object> card = asMap(report.get("card"));
        assertThat(((Number) card.get("sampledSeconds")).intValue()).isEqualTo(2);
        assertThat(((Number) card.get("avgRps")).doubleValue()).isEqualTo(50.0);
        assertThat(((Number) card.get("maxRps")).doubleValue()).isEqualTo(60.0);
        assertThat(((Number) card.get("peakSecondP95Ms")).doubleValue()).isEqualTo(200.0);
        assertThat(((Number) card.get("avgErrorRate")).doubleValue())
                .isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) card.get("authRequestCount")).intValue()).isEqualTo(4);
        assertThat(((Number) card.get("authAvgMs")).doubleValue()).isEqualTo(35.0);
        assertThat(card.get("authSeconds")).isNotNull();
    }

    @Test
    void authValuesNeverLeakIntoThreeLineAggregates() {
        long scenarioId = newScenario(null, null);
        long withAuth = launchSeedAndRelease(scenarioId, framesWithAuth());
        long scenarioId2 = newScenario(null, null);
        long noAuth = launchAndSeedFrames(scenarioId2, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null)));

        Map<String, Object> a = asMap(perfReportService.build(projectId, withAuth).get("card"));
        Map<String, Object> b = asMap(perfReportService.build(projectId, noAuth).get("card"));
        assertThat(a.get("avgRps")).isEqualTo(b.get("avgRps"));
        assertThat(a.get("maxRps")).isEqualTo(b.get("maxRps"));
        assertThat(a.get("peakSecondP95Ms")).isEqualTo(b.get("peakSecondP95Ms"));
        assertThat(a.get("avgErrorRate")).isEqualTo(b.get("avgErrorRate"));
        assertThat(((Number) b.get("authRequestCount")).intValue()).isZero();
        assertThat(b.get("authAvgMs")).isNull();
    }

    @Test
    void trafficLightVerdictDelegatesToSlaJudgeSingleSource() {
        long scenarioId = newScenario(null, Map.of("p95Ms", 150.0));
        long runId = launchAndSeedFrames(scenarioId, framesWithAuth());
        Map<String, Object> verdict = asMap(perfReportService.build(projectId, runId).get("verdict"));

        assertThat((Boolean) verdict.get("passed")).isFalse();
        assertThat(breachedStrings(verdict.get("breachedGates"))).containsExactly("压测响应时间 P95");
        assertThat(String.valueOf(verdict.get("conclusion")))
                .contains("压测响应时间 P95").contains("超阈值 150ms");
    }

    @Test
    void caliberFootnoteIsProgrammaticallyGenerated() {
        long scenarioId = newScenario(null, null);
        long runId = launchAndSeedFrames(scenarioId, framesWithAuth());
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");
        String footnote = String.valueOf(perfReportService.build(projectId, runId).get("caliberFootnote"));

        assertThat(footnote).contains("采样窗口 2 秒");
        assertThat(footnote).contains("ceil(0.95n)");
        assertThat(footnote).contains("预热");
    }

    @Test
    void driverLimitBoundaryMarkedOrHonestlyUndisclosed() {
        long disclosed = newScenario(200, null);
        long run1 = launchSeedAndRelease(disclosed, framesWithAuth());
        Map<String, Object> report1 = perfReportService.build(projectId, run1);
        assertThat(report1.get("driverLimit")).isNotNull();
        assertThat(String.valueOf(report1.get("boundaryNote"))).contains("200");
        // B-5: frames without host samples = no strain segment (honest absence;
        // the legacy note stands unchanged — never a fabricated reading).
        assertThat(String.valueOf(report1.get("boundaryNote")))
                .doesNotContain("驱动侧瓶颈可疑");

        long undisclosed = newScenario(null, null);
        long run2 = launchAndSeedFrames(undisclosed, framesWithAuth());
        Map<String, Object> report2 = perfReportService.build(projectId, run2);
        assertThat(report2.get("driverLimit")).isNull();
        assertThat(String.valueOf(report2.get("boundaryNote")))
                .contains("未披露").contains("被测上限");
    }

    /** B-5 a saturated press box (sampled loadRatio above the K8 ceiling)
 * is honestly named in the boundary note — the flattening curve may come from
 * the engine host, not the target; below-ceiling samples mark the strain
 * hypothesis as not holding. D3 caliber: the verdict gate still only judges
 * the target ceiling. */
    @Test
    void pressBoxStrainSampledAboveCeilingMarksDriverSuspected() {
        long scenarioId = newScenario(200, null);
        List<PerfMetricFrame> strained = List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null,
                        40L, 0L, 4800.0, null, null, "",
                        "{\"load1\": 1.2, \"cores\": 4, \"loadRatio\": 0.3, \"memAvailMb\": 2048.0}"),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null,
                        60L, 0L, 7200.0, null, null, "",
                        "{\"load1\": 10.5, \"cores\": 4, \"loadRatio\": 2.625, \"memAvailMb\": 512.0}"));
        long runId = launchSeedAndRelease(scenarioId, strained);

        String note = String.valueOf(perfReportService.build(projectId, runId).get("boundaryNote"));
        assertThat(note).contains("2.625").contains("0.85").contains("驱动侧瓶颈可疑");

        long calmScenario = newScenario(200, null);
        List<PerfMetricFrame> calm = List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null,
                        40L, 0L, 4800.0, null, null, "",
                        "{\"load1\": 1.2, \"cores\": 4, \"loadRatio\": 0.3, \"memAvailMb\": 2048.0}"));
        long calmRun = launchAndSeedFrames(calmScenario, calm);
        String calmNote = String.valueOf(perfReportService.build(projectId, calmRun).get("boundaryNote"));
        assertThat(calmNote).contains("未超阈值").contains("0.3");
    }

    @Test
    void crossProjectAndMissingRunRejectedWithoutLeakage() {
        long otherProjectCode = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES ('他项目', ?, 1)",
                "PERFREPX" + otherProjectCode);
        long otherProject = lastId("tp_project");
        long scenarioId = newScenario(null, null);
        long runId = launchAndSeedFrames(scenarioId, framesWithAuth());
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFREPX%'");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> perfReportService.build(otherProject, runId))
                .isInstanceOf(com.sixbean.testpike.common.exception.ServiceException.class)
                .hasMessageContaining("不存在");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> perfReportService.build(projectId, runId + 999_999L))
                .isInstanceOf(com.sixbean.testpike.common.exception.ServiceException.class)
                .hasMessageContaining("不存在");
    }

    /**
 * CF-1 contract: a LADDER run's report carries the ceiling-search row
 * (tiers grouped back from the pinned snapshot's staircase + SLO-safe watermark on
 * the last passing step); any other pressure model honestly ships no ceiling row.
 */
    @Test
    void ladderRunReportCarriesCeilingSearchRow() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "寻顶阶梯场景_" + System.nanoTime(), envId, "LADDER",
                Map.of("startRps", 100, "stepRps", 100, "stepSeconds", 10, "steps", 4), null,
                null, List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")),
                Map.of("errorRate", 0.05)));
        // 40 aggregate seconds: step 3 (offsets 20..29) carries a 20% error rate, the
        // rest are clean -> first breach at step 3, last pass step 2, watermark 300x0.8.
        List<PerfMetricFrame> frames = new java.util.ArrayList<>();
        for (int s = 0; s < 40; s++) {
            double errorRate = (s >= 20 && s < 30) ? 0.2 : 0.0;
            frames.add(new PerfMetricFrame(s, 300.0, 50.0, errorRate,
                    null, null, null, null, null));
        }
        long runId = launchAndSeedFrames(scenarioId, frames);
        Map<String, Object> ceiling = asMap(perfReportService.build(projectId, runId).get("ceiling"));

        assertThat((Boolean) ceiling.get("applicable")).isTrue();
        assertThat(ceiling.get("breachStep")).isEqualTo(3);
        assertThat(ceiling.get("lastPassStep")).isEqualTo(2);
        assertThat(((Number) ceiling.get("safeWatermarkRps")).doubleValue())
                .isEqualTo(240.0);
        assertThat(String.valueOf(ceiling.get("conclusion"))).contains("400").contains("240");
    }

    @Test
    void nonLadderRunReportOmitsCeilingRowHonestly() {
        long scenarioId = newScenario(null, null); // CONSTANT_RPS
        long runId = launchAndSeedFrames(scenarioId, framesWithAuth());
        assertThat(perfReportService.build(projectId, runId).get("ceiling")).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<String> breachedStrings(Object raw) {
        return (List<String>) raw;
    }
}
