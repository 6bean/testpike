package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * Partial-frame heartbeat ingestion tests .
 * <p>perf_worker ships in-press frames with heartbeats through the 03 §2.2 {@code
 * perfPartial} trailing key (rows = {runId, seq, frames[]}, frame shape identical to
 * the §2.4 v2.59 widened frames). The executor never names the project — the run row
 * is the reverse lookup anchor (misdelivery guard: a stale or foreign runId can only
 * land on its own project segment). Ingestion reuses the single PerfMetricService
 * gate (no second rule set): same-anchor replay stays idempotent, a different rps is
 * refused in human words, and frames arriving after the run reached a terminal state
 * are rejected by requireRunningRun. Every refusal degrades to a named warning —
 * the heartbeat main chain is never dragged down (monitorFrames precedent).
 * <p>Real chain, no mocks: dev DB direct queries, fixture cascade-cleaned per the
 * PerfRunResultFinalizerTest pattern (PERFPART prefix).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfPartialFrameIngestorTest {

    @Autowired
    private PerfPartialFrameIngestor perfPartialFrameIngestor;

    @Autowired
    private PerfMetricService perfMetricService;

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
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-part-ex-",
                "GATLING", "K6");
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "部分帧测试项目_", "PERFPART", "性能-部分帧", 1,
                "http://perf-target.local/api", 1000, null);
        projectId = seeded[0];
        envId = seeded[1];
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-part-ex-");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFPART%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFPART%'");
    }

    /** Launch a real run (status=1) through the scenario+launch services — the same
     *  ownership chain the heartbeat ingestion reverse-looks-up on. */
    private long newRun() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "部分帧场景", envId, "RAMP",
                Map.of("startRps", 10, "peakRps", 100, "rampSeconds", 60,
                        "holdSeconds", 120, "durationSeconds", 300),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
        return perfRunService.launch(projectId, scenarioId, null);
    }

    private Map<String, Object> frame(int secondOffset, double rps) {
        return Map.of("secondOffset", secondOffset, "rps", rps, "p95Ms", 88.0,
                "errorRate", 0.0);
    }

    private Map<String, Object> partialRow(long runId, int seq, List<Map<String, Object>> frames) {
        return Map.of("runId", runId, "seq", seq, "frames", frames);
    }

    /** Happy path: a well-formed partial row lands frames in the metric table through
     *  the runId -> project reverse lookup (executor side never declares projectId). */
    @Test
    void ingest_validPartialRow_landsFramesThroughRunReverseLookup() {
        long runId = newRun();

        int stored = perfPartialFrameIngestor.ingest(List.of(
                partialRow(runId, 1, List.of(frame(0, 10.0), frame(1, 12.5)))));

        assertThat(stored).isEqualTo(2);
        List<Map<String, Object>> rows = perfMetricService.listFrames(projectId, runId);
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(1).get("rps")).doubleValue()).isEqualTo(12.5);
    }

    /** Replay discipline: the same anchor with the same value stays idempotent (no
     *  error, no duplicate); the same anchor with a different rps is refused in human
     *  words inside the ingestor — never rethrown into the heartbeat caller. */
    @Test
    void ingest_replaySameValueIdempotent_differentValueRefusedQuietly() {
        long runId = newRun();
        perfPartialFrameIngestor.ingest(List.of(
                partialRow(runId, 1, List.of(frame(0, 10.0)))));

        // same anchor, same value: idempotent, must not throw and must not duplicate
        perfPartialFrameIngestor.ingest(List.of(
                partialRow(runId, 1, List.of(frame(0, 10.0)))));
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(1);

        // same anchor, different value: refused at the single ingestion gate, degraded
        // to a named warning — heartbeat main chain unaffected, stored count unchanged
        perfPartialFrameIngestor.ingest(List.of(
                partialRow(runId, 2, List.of(frame(0, 99.0)))));
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(1);
        assertThat(((Number) perfMetricService.listFrames(projectId, runId).get(0)
                .get("rps")).doubleValue()).isEqualTo(10.0);
    }

    /** Late frames after the run reached its terminal state are rejected by the same
     *  requireRunningRun gate the result channel uses (live curve stays frozen at the
     *  finalization snapshot; no second rule set here). */
    @Test
    void ingest_framesArrivingAfterRunFinished_areRejectedWithoutRethrow() {
        long runId = newRun();
        perfRunService.finish(projectId, runId, 2, "压测运行正常结束");

        perfPartialFrameIngestor.ingest(List.of(
                partialRow(runId, 9, List.of(frame(0, 10.0)))));

        assertThat(perfMetricService.listFrames(projectId, runId)).isEmpty();
    }

    /** Misdelivery guard: an unknown/foreign runId (row missing) is named-dropped —
     *  the ingestor has no project context to fall back to and never guesses one. */
    @Test
    void ingest_unknownRunId_isNamedDroppedWithoutRethrow() {
        long runId = newRun();

        int stored = perfPartialFrameIngestor.ingest(List.of(
                partialRow(9_999_999_999L, 1, List.of(frame(0, 10.0))),
                partialRow(runId, 2, List.of(frame(0, 11.0)))));

        // the dirty row is dropped, the good row still lands (entry-level isolation)
        assertThat(stored).isEqualTo(1);
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(1);
    }

    /** Dirty shapes (non-map rows, missing runId, frames not a list) skip honestly —
     *  a heartbeat payload is never allowed to crash core consumption. */
    @Test
    void ingest_dirtyRowShapes_skipHonestlyWithoutRethrow() {
        long runId = newRun();

        List<Object> dirty = new java.util.ArrayList<>();
        dirty.add("not-a-map");
        dirty.add(Map.of("seq", 1, "frames", List.of(frame(0, 1.0))));
        dirty.add(partialRow(runId, 3, List.of()));
        List<Object> junkFrames = new java.util.ArrayList<>();
        junkFrames.add("frame-not-a-map");
        dirty.add(Map.of("runId", runId, "seq", 4, "frames", junkFrames));

        int stored = perfPartialFrameIngestor.ingest(dirty);

        // nothing lands: non-map row dropped, missing-runId row dropped, empty
        // frames skip, all-junk frame list yields zero contract frames (codec
        // skips non-map items honestly — same posture as the result channel)
        assertThat(stored).isZero();
        assertThat(perfMetricService.listFrames(projectId, runId)).isEmpty();
    }
}
