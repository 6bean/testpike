package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfEventRow;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfMetricServiceTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-perf-metric-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.config.domain.service.ConfigService configService;

    private long projectId;
    private long envId;
    private long scenarioId;
    private long runId;

    @BeforeEach
    void seedFixtures() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6");

        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        String projectCode = "PERFMET" + uniq;
        // Ids are read back through RETURNING so a foreign fixture row with an
        // overridden id can never out-rank max(id) and re-parent these rows.
        projectId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测采集测试项目_" + uniq, projectCode);
        envId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-采集测', 1, 'http://perf-target.local/api')",
                projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
        scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "采集契约场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
        runId = perfRunService.launch(projectId, scenarioId, null);
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
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFMET%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFMET%'");
    }

    /** Ownership-negative probes need a project that is NOT the fixture's own. Seeding a
     *  dedicated foreign project (PERFMET-prefixed so cleanupRows removes it) keeps the
     *  test honest even when the dev database holds no other project rows — the earlier
     *  `max(id) WHERE code NOT LIKE 'PERFMET%'` query returned null after E2E purges and
     *  blew up on the unboxing, coupling unit-test isolation to ambient data. */
    private long seedForeignProject() {
        long uniq = System.nanoTime() % 1_000_000_000L;
        return com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "perf-metric-foreign-" + uniq, "PERFMETFO" + uniq);
    }

    private static PerfMetricFrame frame(int offset, double rps, double p95, double errRate) {
        return new PerfMetricFrame(offset, rps, p95, errRate, null, null, null, null, null);
    }


    /** Bucket columns round-trip as first-class columns: req/fail/duration-sum/histogram/
     *  error-class/step-key are all readable back after ingestion. */
    @Test
    void bucketColumnsRoundTripAsFirstClassColumns() {
        int inserted = perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 100.0, 120.0, 0.02, null, null, null, null, null,
                        100L, 2L, 9500.0, "[95,0,0,5]", "{\"timeout\":1,\"http5xx\":1}", "下单")));
        assertThat(inserted).isEqualTo(1);
        Map<String, Object> row = perfMetricService.listFrames(projectId, runId).get(0);
        assertThat(((Number) row.get("req_count")).longValue()).isEqualTo(100L);
        assertThat(((Number) row.get("fail_count")).longValue()).isEqualTo(2L);
        assertThat(String.valueOf(row.get("duration_sum_ms")).replace(".00", ""))
                .isEqualTo("9500");
        // jsonb read-back is PG-normalized text (spaces after commas); compare after stripping
        assertThat(String.valueOf(row.get("hist")).replaceAll("\\s", "")).isEqualTo("[95,0,0,5]");
        assertThat(String.valueOf(row.get("error_class"))).contains("http5xx");
        assertThat(String.valueOf(row.get("step_key"))).isEqualTo("下单");
    }

    /** Same second, different step keys = separate frames (third column of the migrated
     *  unique anchor; prerequisite of the per-step lines). */
    @Test
    void sameSecondDifferentStepKeysCoexistUnderMigratedAnchor() {
        int inserted = perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 100.0, 0.0, null, null, null, null, null,
                        40L, 0L, 4000.0, "[40]", null, "查询"),
                new PerfMetricFrame(0, 60.0, 110.0, 0.0, null, null, null, null, null,
                        60L, 0L, 6600.0, "[60]", null, "下单")));
        assertThat(inserted).isEqualTo(2);
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(2);
    }

    /** B-5 the host sample rides the frame as a first-class jsonb column
 * (tp_perf_metric.host_stats) — persisted, readable back, and honestly NULL for
 * frames without a sample (legacy executor / unreadable proc). A fabricated
 * 0-substituted reading is forbidden on this face too. */
    @Test
    void hostStatsRoundTripAsFirstClassColumnAndAbsenceStaysNull() {
        int inserted = perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 100.0, 120.0, 0.02, null, null, null, null, null,
                        100L, 2L, 9500.0, null, null, "",
                        "{\"load1\": 3.4, \"cores\": 4, \"loadRatio\": 0.85, \"memAvailMb\": 1024.0}"),
                new PerfMetricFrame(1, 90.0, 130.0, 0.01, null, null, null, null, null)));
        assertThat(inserted).isEqualTo(2);

        List<Map<String, Object>> rows = perfMetricService.listFrames(projectId, runId);
        assertThat(String.valueOf(rows.get(0).get("host_stats")).replaceAll("\\s", ""))
                .contains("\"loadRatio\":0.85").contains("\"cores\":4");
        assertThat(rows.get(1).get("host_stats")).isNull();
    }

    /** failCount above reqCount = row-numbered plain-worded rejection (service-side value
     *  guard plus the DB check constraint — two lines of defense). */
    @Test
    void failCountAboveReqCountRejectedByRowIndexInPlainWords() {
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 10.0, 100.0, 0.5, null, null, null, null, null,
                        3L, 5L, 300.0, null, null, ""))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 1 帧")
                .hasMessageContaining("大于请求数");
    }

    /** The O10 v2.0 caliber SQL executes against real ingested frames (the registry is the
     *  retrieval authority; the three perf calibers never ran against the live DB before). */
    @Test
    void perfV2CalibersExecuteAgainstIngestedBucketFrames() {
        // Bucket slots follow PerfHistogram.boundaries(): index 2 -> bound 15ms, index 3 -> bound 20ms.
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 100.0, 12.0, 1.0, null, null, null, null, null,
                        1L, 1L, 10.0, "[0,0,1]", null, ""),
                new PerfMetricFrame(1, 100.0, 20.0, 0.0, null, null, null, null, null,
                        1000L, 0L, 15000.0, "[0,0,0,1000]", null, "")));
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate);
        java.util.Map<String, Object> p = java.util.Map.of("runId", runId);

        Double err = named.queryForObject(
                com.sixbean.testpike.common.report.CaliberService
                        .resolve("perf-error-rate", p).sql(), new org.springframework
                        .jdbc.core.namedparam.MapSqlParameterSource(p), Double.class);
        // weighted caliber = 1/1001 ~ 0.001 (the equal-weight second mean 0.5 is the old
        // unsound reading; v2.0 must return the request-count-weighted value)
        assertThat(err).isCloseTo(0.001, org.assertj.core.data.Offset.offset(1e-6));

        Double p95 = named.queryForObject(
                com.sixbean.testpike.common.report.CaliberService
                        .resolve("perf-p95", p).sql(), new org.springframework
                        .jdbc.core.namedparam.MapSqlParameterSource(p), Double.class);
        // Merged buckets: 1 sample at bound 15, 1000 samples at bound 20. Nearest rank
        // ceil(0.95 x 1001) = 951 first reached at the 20ms bound -> whole-run P95 = 20.
        assertThat(p95).isEqualTo(20.0);

        Double thr = named.queryForObject(
                com.sixbean.testpike.common.report.CaliberService
                        .resolve("perf-throughput", p).sql(), new org.springframework
                        .jdbc.core.namedparam.MapSqlParameterSource(p), Double.class);
        assertThat(thr).isCloseTo(500.5, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void threeLineFramesPersistWithAiFieldsAsFirstClassColumns() {
        int inserted = perfMetricService.recordFrames(projectId, runId, List.of(
                frame(0, 48.0, 120.0, 0.0),
                new PerfMetricFrame(1, 50.0, 95.5, 0.02, 310.0, 12.5, 88.0, null, null),
                frame(2, 51.0, 130.0, 0.0)));
        assertThat(inserted).isEqualTo(3);
        List<Map<String, Object>> frames = perfMetricService.listFrames(projectId, runId);
        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(f -> ((Number) f.get("second_offset")).intValue())
                .containsExactly(0, 1, 2);
        Map<String, Object> ai = frames.get(1);
        assertThat(((Number) ai.get("ttft_ms")).doubleValue()).isEqualTo(310.0);
        assertThat(((Number) ai.get("itl_ms")).doubleValue()).isEqualTo(12.5);
        assertThat(((Number) ai.get("tokens_per_sec")).doubleValue()).isEqualTo(88.0);
        assertThat(frames.get(0).get("ttft_ms")).isNull();
    }

    @Test
    void replayOfSameOffsetsIsIdempotentOnRunAnchor() {
        List<PerfMetricFrame> first = List.of(frame(0, 40.0, 100.0, 0.0), frame(1, 42.0, 110.0, 0.0));
        perfMetricService.recordFrames(projectId, runId, first);
        int replayed = perfMetricService.recordFrames(projectId, runId, first);
        assertThat(replayed).isZero();
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(2);
        assertThat(((Number) perfMetricService.listFrames(projectId, runId).get(0)
                .get("rps")).doubleValue()).isEqualTo(40.0);
    }

    @Test
    void divergentReplayOfSameOffsetRejectedAsMisfire() {
        perfMetricService.recordFrames(projectId, runId, List.of(frame(3, 40.0, 100.0, 0.0)));
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId,
                List.of(frame(3, 99.0, 999.0, 0.5))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("重放内容不一致")
                .hasMessageContaining("secondOffset」= 3");
        assertThat(((Number) perfMetricService.listFrames(projectId, runId).get(0)
                .get("rps")).doubleValue()).isEqualTo(40.0);
    }

    @Test
    void finishedRunRejectsLateFramesInPlainWords() {
        perfRunService.finish(projectId, runId, 3, "错误率熔断");
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId,
                List.of(frame(0, 1.0, 1.0, 0.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已结束")
                .hasMessageContaining("不再写入曲线数据");
    }

    @Test
    void crossProjectRunOwnershipRejectedWithoutLeak() {
        // Ownership rejection fires before any row lookup, so an id from the
        // ghost band (verified empty on the live dev db) is a valid
        // foreign-project probe without seeding or a global max read.
        long other = 999_999_999L;
        assertThatThrownBy(() -> perfMetricService.recordFrames(other, runId,
                List.of(frame(0, 1.0, 1.0, 0.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不存在或不属于项目");
    }

    @Test
    void illegalFrameValuesRejectedByRowIndexInPlainWords() {
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId,
                List.of(frame(0, 10.0, 100.0, 0.0), frame(1, 10.0, 100.0, 1.5))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 2 帧")
                .hasMessageContaining("1.5");
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId,
                List.of(frame(-3, 10.0, 100.0, 0.0))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 1 帧")
                .hasMessageContaining("secondOffset");
    }

    @Test
    void oversizedBatchRejectedWithVolumeGate() {
        List<PerfMetricFrame> flood = new ArrayList<>();
        for (int i = 0; i < 7201; i++) {
            flood.add(frame(i, 1.0, 1.0, 0.0));
        }
        assertThatThrownBy(() -> perfMetricService.recordFrames(projectId, runId, flood))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("7200")
                .hasMessageContaining("拆分");
    }

    // ---------- B-6 error-class TopN ----------

    /** Error-class TopN aggregates the fixed five-key counts across un-split frames of the
     *  run, descending by count (raw material of the error drawer). Per-step frames are
     *  excluded on purpose: the same requests are already counted in the aggregate frame,
     *  summing both calibers would double-count (B-4 line-vs-total separation rule). */
    @Test
    void errorClassTopNSumsAggregateFramesDescendingExcludingStepLines() {
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 100.0, 120.0, 0.06, null, null, null, null, null,
                        100L, 6L, 9000.0, null, "{\"http5xx\":5,\"timeout\":1}", ""),
                new PerfMetricFrame(0, 50.0, 110.0, 0.18, null, null, null, null, null,
                        50L, 9L, 5500.0, null, "{\"biz\":9}", "下单"),
                new PerfMetricFrame(1, 80.0, 130.0, 0.04, null, null, null, null, null,
                        80L, 3L, 8000.0, null, "{\"timeout\":3}", "")));
        List<PerfErrorClassCount> top = perfMetricService.errorClassTopN(projectId, runId);
        // aggregate caliber only: http5xx=5, timeout=1+3=4; biz=9 lives on the step line
        assertThat(top).extracting(PerfErrorClassCount::errorClass)
                .containsExactly("http5xx", "timeout");
        assertThat(top.get(0).count()).isEqualTo(5L);
        assertThat(top.get(1).count()).isEqualTo(4L);
    }

    /** Frames without error-class payload (legacy shape or clean seconds) contribute no
     *  rows; a run with nothing collected yields an honest empty list, never a fabricated
     *  zero-per-class wall. */
    @Test
    void errorClassTopNOnUnCollectedRunReturnsEmpty() {
        perfMetricService.recordFrames(projectId, runId, List.of(
                frame(0, 10.0, 100.0, 0.0)));
        assertThat(perfMetricService.errorClassTopN(projectId, runId)).isEmpty();
    }

    /** Cross-project ownership: another project's run id answers the same honest empty as
     *  'not yours, not leaking' — the read face filters by (run, project) like frames do. */
    @Test
    void errorClassTopNOfForeignProjectRunStaysEmpty() {
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 10.0, 100.0, 0.1, null, null, null, null, null,
                        10L, 1L, 900.0, null, "{\"refused\":1}", "")));
        long otherProject = seedForeignProject();
        assertThat(perfMetricService.errorClassTopN(otherProject, runId)).isEmpty();
    }

    // ---------- live curve ----------

    /** b5 cursor: the live-page poll hands back the last second it saw and receives
 * only strictly-later seconds in ascending order (the full-window payload per 2s poll
 * was the blow-up GA-3 fixes — a 4h soak must never re-ship 14400 rows every poll). */
    @Test
    void liveCurveAppendsOnlySecondsAfterTheCursorInAscendingOrder() {
        perfMetricService.recordFrames(projectId, runId, List.of(
                frame(0, 1.0, 10.0, 0.0), frame(1, 2.0, 10.0, 0.0),
                frame(2, 3.0, 10.0, 0.0), frame(3, 4.0, 10.0, 0.0),
                frame(4, 5.0, 10.0, 0.0)));
        com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView view =
                perfMetricService.liveCurve(projectId, runId, 2, null);
        List<Integer> offsets = view.frames().stream()
                .map(f -> ((Number) f.get("second_offset")).intValue()).toList();
        assertThat(offsets).containsExactly(3, 4);
        assertThat(view.sampled()).isFalse();
        assertThat(view.bucketSeconds()).isEqualTo(1);
        assertThat(view.lastOffset()).isEqualTo(4);
        assertThat(view.collectedTotal()).isEqualTo(2L);
    }

    /** b5 downsample: an oversized window collapses into ceil(n/maxPoints)-second
 * buckets, the p95 peak survives as the bucket max (live view must never hide a
 * latency spike), and the response says sampled=true honestly. */
    @Test
    void liveCurveDownsamplesWideWindowsAndKeepsPeakLatency() {
        List<PerfMetricFrame> series = new ArrayList<>();
        for (int s = 0; s < 500; s++) {
            series.add(frame(s, 10.0, s == 300 ? 9000.0 : 100.0, 0.0));
        }
        perfMetricService.recordFrames(projectId, runId, series);
        com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView view =
                perfMetricService.liveCurve(projectId, runId, null, 50);
        assertThat(view.sampled()).isTrue();
        assertThat(view.collectedTotal()).isEqualTo(500L);
        assertThat(view.frames().size()).isLessThanOrEqualTo(52);
        assertThat(view.bucketSeconds()).isGreaterThanOrEqualTo(10);
        double peak = view.frames().stream()
                .mapToDouble(f -> ((Number) f.get("p95_ms")).doubleValue()).max().orElse(0);
        assertThat(peak).isEqualTo(9000.0);
        assertThat(view.lastOffset()).isEqualTo(499);
    }

    /** b5 caliber: a sampled bucket's error rate is the request-weighted sum
 * (Σfail/Σreq), not the arithmetic mean of per-second rates — the same O10
 * weighted caliber the full-run judge uses, so curve and verdict never diverge. */
    @Test
    void liveCurveSampledBucketUsesWeightedErrorRateWhenBucketsPresent() {
        List<PerfMetricFrame> series = new ArrayList<>();
        // 9 quiet-volume seconds at a high rate (req=10 fail=5) + 1 heavy second at a low
        // rate (req=1000 fail=10): weighted = 55/1090 = 0.0505; the mean of rates would
        // inflate to 0.451 — low-load seconds must not dominate the curve (O10 caliber).
        for (int s = 0; s < 9; s++) {
            series.add(new PerfMetricFrame(s, 10.0, 10.0, 0.5, null, null, null, null, null,
                    10L, 5L, 100.0, null, null, ""));
        }
        series.add(new PerfMetricFrame(9, 1000.0, 12.0, 0.01, null, null, null, null, null,
                1000L, 10L, 12000.0, null, null, ""));
        perfMetricService.recordFrames(projectId, runId, series);
        com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView view =
                perfMetricService.liveCurve(projectId, runId, null, 1);
        assertThat(view.frames()).hasSize(1);
        double err = ((Number) view.frames().get(0).get("error_rate")).doubleValue();
        assertThat(err).isCloseTo(0.0505, org.assertj.core.data.Offset.offset(0.001));
        // rps bucket mean over 10 seconds = (9*10 + 1000)/10 = 109
        double rps = ((Number) view.frames().get(0).get("rps")).doubleValue();
        assertThat(rps).isCloseTo(109.0, org.assertj.core.data.Offset.offset(0.01));
    }

    /** b5 ownership: another project's run answers the same honest empty as the
 * frames face — (run, project) filter, no leak, no exception. */
    @Test
    void liveCurveOnForeignProjectRunStaysEmpty() {
        perfMetricService.recordFrames(projectId, runId, List.of(frame(0, 1.0, 10.0, 0.0)));
        long otherProject = seedForeignProject();
        com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView view =
                perfMetricService.liveCurve(otherProject, runId, null, null);
        assertThat(view.frames()).isEmpty();
        assertThat(view.lastOffset()).isNull();
        assertThat(view.collectedTotal()).isZero();
    }

    /** b5 K8: the point ceiling is hot-updatable through ConfigService (never a
 * hardcoded call-site number); a dirty value falls back to the safe default. */
    @Test
    void liveCurveMaxPointsFollowsHotUpdatedK8Key() {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, "perf.live-curve.max-points");
        org.junit.jupiter.api.Assertions.assertNotNull(id,
                "perf.live-curve.max-points seed row must exist (V276)");
        List<PerfMetricFrame> series = new ArrayList<>();
        for (int s = 0; s < 100; s++) {
            series.add(frame(s, 5.0, 20.0, 0.0));
        }
        perfMetricService.recordFrames(projectId, runId, series);
        try {
            hotSetConfig("perf.live-curve.max-points", "20");
            assertThat(perfMetricService.liveCurve(projectId, runId, null, null)
                    .frames().size()).isLessThanOrEqualTo(20);
            hotSetConfig("perf.live-curve.max-points", "dirty-not-a-number");
            // dirty value = fallback default 600 > 100 rows -> honest unsampled
            assertThat(perfMetricService.liveCurve(projectId, runId, null, null)
                    .sampled()).isFalse();
        } finally {
            hotSetConfig("perf.live-curve.max-points", "600");
        }
    }

    private void hotSetConfig(String key, String value) {
        hotSetConfig(key, value,
                "压测实况曲线单屏最大点数（超出=服务端按秒窗聚合降采样，峰值时延保留；报告页始终全量口径）");
    }

    /** Single hot-set helper: replace the row by key so tests never carry a
     *  second upsert path; the description travels with the key under test. */
    private void hotSetConfig(String key, String value, String description) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                key, value, "系统", "int", 0, description));
    }


    @Test
    void eventsPersistAndQueryByRun() {
        int n = perfMetricService.recordEvents(projectId, runId, List.of(
                new PerfEventRow(12, "CIRCUIT_BREAK", "错误率 12% 超阈值 5%，自动停止"),
                new PerfEventRow(15, "WORKER_TRACEBACK", "java.net.ConnectException: refused")));
        assertThat(n).isEqualTo(2);
        List<Map<String, Object>> events = perfMetricService.listEvents(projectId, runId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_kind")).isEqualTo("CIRCUIT_BREAK");
        assertThat((String) events.get(1).get("detail")).contains("ConnectException");
    }

    /**
     * U-10 ingest-point masking: event detail is free text from the executor
     * (worker traceback, TLS notes) that can carry phone numbers or e-mail
     * addresses. The shared spectrum masks it record-time so the stored row —
     * not just the read-out — never keeps raw PII.
     */
    @Test
    void eventDetailIsMaskedAtIngestThroughSharedSpectrum() {
        int n = perfMetricService.recordEvents(projectId, runId, List.of(
                new PerfEventRow(20, "WORKER_TRACEBACK",
                        "callback to 13812345678 failed for zhangsan@example.com")));

        assertThat(n).isEqualTo(1);
        List<Map<String, Object>> events = perfMetricService.listEvents(projectId, runId);
        String stored = (String) events.get(events.size() - 1).get("detail");
        assertThat(stored).doesNotContain("13812345678");
        assertThat(stored).doesNotContain("zhangsan@example.com");
        assertThat(stored).contains("138****5678");
        assertThat(stored).contains("z***@example.com");
    }

    @Test
    void eventShapeViolationsRejectedInPlainWords() {
        assertThatThrownBy(() -> perfMetricService.recordEvents(projectId, runId,
                List.of(new PerfEventRow(1, " ", "空类型"))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("「eventKind」为空");
        assertThatThrownBy(() -> perfMetricService.recordEvents(projectId, runId,
                List.of(new PerfEventRow(1, "ERR", "x".repeat(4001)))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("4000")
                .hasMessageContaining("摘要");
    }

    /**
     * R67/R57 bounded event window at the write gate: stored rows per run are
     * trimmed to the newest N right after each ingest batch (hot key
     * perf.run.events-tail-cap), oldest first. The drawer read-out and the
     * stored set share one ceiling — a long run can never accumulate an
     * unbounded event leg.
     */
    @Test
    void eventIngestEvictsOldestRowsBeyondHotTailCap() {
        hotSetConfig("perf.run.events-tail-cap", "10",
                "压测事件流每跑保留条数上限（超出即淘汰最早事件，运行结果不受影响）");
        try {
            List<PerfEventRow> batch = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                batch.add(new PerfEventRow(i, "WORKER_TRACEBACK", "tail-event-" + i));
            }
            int inserted = perfMetricService.recordEvents(projectId, runId, batch);

            assertThat(inserted).isEqualTo(12);
            List<Map<String, Object>> stored = perfMetricService.listEvents(projectId, runId);
            assertThat(stored).hasSize(10);
            assertThat(stored.stream()
                    .map(e -> ((Number) e.get("second_offset")).intValue()).toList())
                    .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        } finally {
            hotSetConfig("perf.run.events-tail-cap", "2000",
                    "压测事件流每跑保留条数上限（超出即淘汰最早事件，运行结果不受影响）");
        }
    }

    /** A dirty cap value falls back to the factory default (2000) — it can
     *  neither switch the bound off nor collapse the window below one screen. */
    @Test
    void dirtyEventTailCapFallsBackToFactoryDefault() {
        hotSetConfig("perf.run.events-tail-cap", "dirty-not-a-number",
                "压测事件流每跑保留条数上限（超出即淘汰最早事件，运行结果不受影响）");
        try {
            List<PerfEventRow> batch = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                batch.add(new PerfEventRow(i, "CIRCUIT_BREAK", "fallback-event-" + i));
            }
            perfMetricService.recordEvents(projectId, runId, batch);

            assertThat(perfMetricService.listEvents(projectId, runId)).hasSize(12);
        } finally {
            hotSetConfig("perf.run.events-tail-cap", "2000",
                    "压测事件流每跑保留条数上限（超出即淘汰最早事件，运行结果不受影响）");
        }
    }
}
