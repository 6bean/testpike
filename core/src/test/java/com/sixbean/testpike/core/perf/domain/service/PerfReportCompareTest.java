package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
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
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfReportCompareTest {

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
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-cmp-ex-",
                "GATLING", "K6");
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "版本对比测试项目_", "PERFCMP", "性能-对比测", 1,
                "http://perf-target.local/api", 1000, null);
        projectId = seeded[0];
        envId = seeded[1];
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-cmp-ex-");
        // Fixture-scoped teardown through the shared sweep (R40): the hand-written
        // DELETE list used to omit tp_exec_task, so every dispatched run left a
        // pending task row orphaned against the deleted project (post-test census
        // showed 7 such rows after this suite in the fresh-install CI window).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFCMP%'", Long.class));
    }


    private long newScenario() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "对比契约场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    /** slice-b: a scenario whose DSL (targetRps) genuinely differs — the fingerprint
 * then differs and the cross-scenario rejection stays observable. */
    private long newScenarioWithDifferentDsl() {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "对比契约异DSL场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 80, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
    }

    private long launchAndSeedFrames(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = perfRunService.launch(projectId, scenarioId, null);
        perfMetricService.recordFrames(projectId, runId, frames);
        return runId;
    }

    /** Same-environment comparison fixture: the GA-2 environment execution lock admits one
     *  in-flight load per environment, so each prior run is seeded then finished (compare
     *  does not require a running state) before the next launch proceeds. */
    private long launchSeedAndRelease(long scenarioId, List<PerfMetricFrame> frames) {
        long runId = launchAndSeedFrames(scenarioId, frames);
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");
        return runId;
    }

    private static PerfMetricFrame frame(int offset, double rps, double p95, double err) {
        return new PerfMetricFrame(offset, rps, p95, err, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object raw) {
        return (List<Map<String, Object>>) raw;
    }

    
    @Test
    void alignsBySecondOffsetAndDeltasThreeColumns() {
        long scenarioId = newScenario();
        long runA = launchSeedAndRelease(scenarioId, List.of(
                frame(0, 40.0, 120.0, 0.02),
                frame(1, 50.0, 150.0, 0.04),
                frame(2, 60.0, 200.0, 0.06)));
        long runB = launchAndSeedFrames(scenarioId, List.of(
                frame(0, 30.0, 100.0, 0.01),
                frame(2, 55.0, 210.0, 0.08),
                frame(3, 70.0, 220.0, 0.10)));

        Map<String, Object> compare = perfReportService.compare(projectId, runA, runB);

        List<Map<String, Object>> series = asList(compare.get("series"));
        assertThat(series).hasSize(4);
        assertThat(((Number) series.get(0).get("secondOffset")).intValue()).isZero();
        assertThat(((Number) series.get(3).get("secondOffset")).intValue()).isEqualTo(3);

        List<Map<String, Object>> diffs = asList(compare.get("diffs"));
        assertThat(diffs).hasSize(2);
        assertThat(((Number) diffs.get(0).get("secondOffset")).intValue()).isZero();
        assertThat(((Number) diffs.get(1).get("secondOffset")).intValue()).isEqualTo(2);

        Map<String, Object> d0 = diffs.get(0);
        assertThat(((Number) d0.get("rpsDelta")).doubleValue()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) d0.get("p95DeltaMs")).doubleValue()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) d0.get("errorRateDelta")).doubleValue()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
        Map<String, Object> d2 = diffs.get(1);
        assertThat(((Number) d2.get("rpsDelta")).doubleValue()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) d2.get("p95DeltaMs")).doubleValue()).isCloseTo(-10.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(((Number) d2.get("errorRateDelta")).doubleValue()).isCloseTo(-0.02, org.assertj.core.data.Offset.offset(1e-9));

        // Overlay series carries the honest per-side scalar (RPS per the chart
        // note): second 1 exists only on side A -> a is that RPS number, b stays
        // null (never a fabricated B value, never the whole frame row).
        Map<String, Object> row1 = series.get(1);
        assertThat(row1.get("a")).isInstanceOf(Number.class);
        assertThat(((Number) row1.get("a")).doubleValue()).isCloseTo(50.0,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(row1.get("b")).isNull();
    }

    /** Contract ③: a cross-scenario pair with different DSLs rejects in human wording.
 * slice-b correction: the original fixture built two scenarios with an
 * identical DSL (names only differed), which the relaxation now legitimately
 * admits as comparable (that spectrum lives in PerfReportSameDslCompareTest).
 * A rejection pair must genuinely differ, so the second scenario uses another
 * targetRps — locking the historical "cross-scenario AND different DSL rejects"
 * discipline. */
    @Test
    void rejectsCrossScenarioCompareWithPlainWords() {
        long runA = launchSeedAndRelease(newScenario(), List.of(frame(0, 40.0, 120.0, 0.0)));
        long runB = launchAndSeedFrames(newScenarioWithDifferentDsl(),
                List.of(frame(0, 30.0, 100.0, 0.0)));

        assertThatThrownBy(() -> perfReportService.compare(projectId, runA, runB))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非同一场景")
                .satisfies(e -> assertThat(((ServiceException) e).getCode())
                        .isEqualTo(BizErrorCode.VALIDATION_ERROR));
    }

    
    @Test
    void honestEmptyDiffTableWhenEitherRunHasNoFrames() {
        long scenarioId = newScenario();
        long withFrames = launchSeedAndRelease(scenarioId, List.of(frame(0, 40.0, 120.0, 0.02)));
        long noFrames = perfRunService.launch(projectId, scenarioId, null);

        Map<String, Object> compare = perfReportService.compare(projectId, withFrames, noFrames);
        assertThat(asList(compare.get("diffs"))).isEmpty();

        Map<String, Object> reversed = perfReportService.compare(projectId, noFrames, withFrames);
        assertThat(asList(reversed.get("diffs"))).isEmpty();
    }

    
    @Test
    void unknownOrForeignRunRejectedWithoutLeakage() {
        long scenarioId = newScenario();
        long runId = launchAndSeedFrames(scenarioId, List.of(frame(0, 40.0, 120.0, 0.0)));

        assertThatThrownBy(() -> perfReportService.compare(projectId, runId, runId + 999_999L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在");
    }
}
