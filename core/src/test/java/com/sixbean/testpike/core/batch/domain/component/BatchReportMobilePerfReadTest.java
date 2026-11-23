package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchReportView;
import com.sixbean.testpike.core.batch.api.dto.MobilePerfMetricView;
import com.sixbean.testpike.core.batch.api.dto.MobilePerfRollupView;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.batch.support.BatchReportFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Report read path for the mobile performance rollup (V486 snapshot columns).
 * <p>Contract under test = verbatim forwarding: {@code BatchService.report} reads
 * {@code tp_batch_report.mobile_perf_json} and exposes every field the finalize
 * rollup stored (median, taskCount, prevMedian, delta, prevBatchId, sampleCount).
 * Editing the task evidence after finalization must not move the numbers the report
 * returns — recomputation at query time is banned, and the read path owns no
 * calculation (TC-I7-01 same-row discipline, carried to the mobile columns).
 * <p>Honest absence is asserted as well: a metric the previous cycle never reported
 * arrives without comparison fields, and a batch with no mobile reading at all
 * arrives as a null rollup rather than an empty one.
 * <p>Real dev DB on this chain's slot, no mocks (strict-testing-standards); row
 * builders, finalize trigger and teardown come from BatchReportFixture (single source).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchReportMobilePerfReadTest {

    @Autowired
    private BatchReportFixture fixture;
    @Autowired
    private BatchService batchService;

    @BeforeEach
    void setUp() {
        fixture.isolateSideChannels();
    }

    @AfterEach
    void tearDown() {
        fixture.sweepFixtureRows();
    }

    // ── ① the stored rollup reaches the report view unchanged ──

    @Test
    void report_view_forwards_mobile_rollup_from_snapshot() {
        long pid = fixture.createProject("MPF1");
        long c1 = fixture.createCase(pid, "case-a");
        long batchId = fixture.finalizedBatch(pid, "mobile rollup", new Object[][]{{c1, 3}, {c1, 3}});
        fixture.setResultExt(batchId, 0, perf("{\"cpuPercent\": {\"median\": 10.0, \"count\": 4}}", 4));
        fixture.setResultExt(batchId, 1, perf("{\"cpuPercent\": {\"median\": 20.0, \"count\": 6}, "
                + "\"launchTimeMs\": {\"median\": 1000.0, \"count\": 6}}", 6));

        fixture.finalizeBatch(batchId);
        // Post-finalize evidence edit: the report must keep answering with the snapshot.
        fixture.setResultExt(batchId, 1, perf("{\"cpuPercent\": {\"median\": 999.0, \"count\": 1}}", 1));

        MobilePerfRollupView rollup = batchService.report(batchId).mobilePerf();
        assertNotNull(rollup, "a batch whose tasks reported mobile metrics must expose the rollup");
        MobilePerfMetricView cpu = rollup.metrics().get("cpuPercent");
        assertNotNull(cpu, "cpuPercent rollup forwarded");
        assertEquals(15.0, cpu.median().doubleValue(), 0.001,
                "report forwards the stored batch median (10 and 20 across two tasks), not a re-read of edited evidence");
        assertEquals(2, cpu.taskCount().intValue(), "both reporting tasks counted");
        assertNull(cpu.prevMedian(), "first batch of the cycle carries no previous median");
        assertNull(cpu.delta(), "and therefore no delta");
        assertEquals(1000.0, rollup.metrics().get("launchTimeMs").median().doubleValue(), 0.001,
                "a metric only one task reported takes that task's own median");
        assertEquals(10L, rollup.sampleCount().longValue(), "sample counters summed at finalize are forwarded");
        assertNull(rollup.prevBatchId(), "no previous cycle = no provenance anchor");
    }

    // ── ② previous-cycle comparison fields survive the read path ──

    @Test
    void report_view_exposes_previous_cycle_median_and_delta() {
        long pid = fixture.createProject("MPF2");
        long c1 = fixture.createCase(pid, "case-a");
        long firstId = fixture.finalizedBatch(pid, "cycle one", new Object[][]{{c1, 3}});
        fixture.setResultExt(firstId, 0, perf("{\"cpuPercent\": {\"median\": 10.0, \"count\": 4}, "
                + "\"memoryMb\": {\"median\": 256.0, \"count\": 4}}", 4));
        fixture.finalizeBatch(firstId);

        long secondId = fixture.finalizedBatch(pid, "cycle two", new Object[][]{{c1, 3}});
        fixture.setResultExt(secondId, 0, perf("{\"cpuPercent\": {\"median\": 25.0, \"count\": 5}, "
                + "\"launchTimeMs\": {\"median\": 800.0, \"count\": 5}}", 5));
        fixture.finalizeBatch(secondId);

        MobilePerfRollupView rollup = batchService.report(secondId).mobilePerf();
        assertNotNull(rollup);
        assertEquals(firstId, rollup.prevBatchId().longValue(), "provenance anchor of the comparison");
        MobilePerfMetricView cpu = rollup.metrics().get("cpuPercent");
        assertEquals(25.0, cpu.median().doubleValue(), 0.001);
        assertEquals(10.0, cpu.prevMedian().doubleValue(), 0.001, "previous cycle median forwarded");
        assertEquals(15.0, cpu.delta().doubleValue(), 0.001, "delta forwarded as stored (median - prevMedian)");
        assertNull(rollup.metrics().get("launchTimeMs").prevMedian(),
                "a metric the previous cycle never reported gets no comparison fields");
        assertTrue(!rollup.metrics().containsKey("memoryMb"),
                "metrics only the previous cycle reported never appear in this batch's rollup");
    }

    // ── ③ honest absence: no mobile reading = null rollup, never an empty object ──

    @Test
    void report_view_mobile_rollup_is_null_for_batch_without_mobile_readings() {
        long pid = fixture.createProject("MPF3");
        long c1 = fixture.createCase(pid, "case-a");
        long batchId = fixture.finalizedBatch(pid, "no mobile", new Object[][]{{c1, 3}, {c1, 4}});

        fixture.finalizeBatch(batchId);

        BatchReportView view = batchService.report(batchId);
        assertNull(view.mobilePerf(), "a plain batch keeps the rollup null (never zero-filled)");
        assertEquals(2, view.total(), "other report dimensions untouched by the new field");
    }

    /** Task result_ext payload carrying the executor trailing key in its stored shape. */
    private static String perf(String metricsJson, int sampleCount) {
        return "{\"mobilePerfSummary\": {\"metrics\": " + metricsJson
                + ", \"sampleCount\": " + sampleCount + "}}";
    }
}
