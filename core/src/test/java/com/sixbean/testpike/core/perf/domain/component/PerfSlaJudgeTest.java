package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-run recomputation tests for the SLA three-gate judge (bucket caliber = request-count
 * weighted error rate + merged-histogram whole-run percentile).
 * <p>Baseline being fixed: the v1.0 reading took max of per-second p95 (worst second window)
 * and the arithmetic mean of per-second error rates — low-load seconds with high error rates
 * get inflated, and per-second quantiles do not merge into a whole-run quantile. The v2.0
 * bucket caliber reads error rate = sum(fail)/sum(req) and P95 = merged histogram at
 * nearest-rank ceil(0.95N) bucket bound. Frames without bucket fields (legacy payloads) fall
 * back to the v1 behavior (one judgment family, degradation only from missing raw material).
 */
class PerfSlaJudgeTest {

    private final PerfSlaJudge judge = new PerfSlaJudge();

    /** Full 15-argument frame builder with bucket fields placed explicitly. */
    private static PerfMetricFrame frame(int second, double rps, double p95Ms, double errorRate,
                                         Long reqCount, Long failCount, String histJson) {
        return new PerfMetricFrame(second, rps, p95Ms, errorRate,
                null, null, null, null, null,
                reqCount, failCount, null, histJson, null, "");
    }

    /** Pack counts at their bucket upper bounds (all other buckets zero) into a hist JSON string. */
    private static String hist(Map<Double, Long> boundToCount) {
        double[] bounds = PerfHistogram.boundaries();
        long[] counts = new long[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            counts[i] = boundToCount.getOrDefault(bounds[i], 0L);
        }
        return PerfHistogram.toJson(counts);
    }

    private static Map<String, Object> runWithSla(Map<String, Object> sla) {
        return Map.of("id", 1L, "sla", sla);
    }

    @Test
    void weightedErrorRateSuppressesLowLoadSecondInflation() {
        // Second 0 carries a single failed request (instantaneous error rate 1.0), second 1
        // carries 1000 clean requests. The arithmetic mean 0.5 would breach the 5% gate
        // (false alarm); the weighted reading 1/1001 ~ 0.1% must pass.
        List<PerfMetricFrame> frames = List.of(
                frame(0, 1, 10, 1.0, 1L, 1L, hist(Map.of(10.0, 1L))),
                frame(1, 1000, 20, 0.0, 1000L, 0L, hist(Map.of(20.0, 1000L))));
        PerfAttribution result = judge.judge(
                runWithSla(Map.of("errorRate", 0.05)), frames);
        assertThat(result.passed())
                .as("weighted error rate 1/1001 must pass 5%% gate despite 50%% arithmetic mean")
                .isTrue();
    }

    @Test
    void fullRunP95UsesHistogramRankInsteadOfWorstSecondMax() {
        // Per-second p95 fields say 10 (low intra-second peaks), but the merged histogram
        // holds 95 requests at bound 50 and 5 requests at bound 500: whole-run P95 = rank 95
        // -> bucket bound 50ms. Against a 40ms limit the v1 max reading passes (missed
        // breach) while the v2 bucket reading must flag it.
        List<PerfMetricFrame> frames = List.of(
                frame(0, 50, 10, 0.0, 50L, 0L, hist(Map.of(50.0, 50L))),
                frame(1, 50, 10, 0.0, 50L, 0L, hist(Map.of(50.0, 45L, 500.0, 5L))));
        PerfAttribution result = judge.judge(
                runWithSla(Map.of("p95Ms", 40L)), frames);
        assertThat(result.breachedGates()).containsExactly("压测响应时间 P95");
        assertThat(result.conclusion()).contains("50");
    }

    @Test
    void legacyFramesWithoutBucketsFallBackToWorstSecondMax() {
        // Legacy frames (all bucket fields null, the pre-step executor payload): fall back to
        // the v1 reading (worst-second max / arithmetic mean) — the judge must not throw and
        // must agree with the old behavior.
        List<PerfMetricFrame> frames = new ArrayList<>();
        frames.add(new PerfMetricFrame(0, 100, 120, 0.12, null, null, null, null, null));
        frames.add(new PerfMetricFrame(1, 100, 200, 0.00, null, null, null, null, null));
        PerfAttribution result = judge.judge(
                runWithSla(Map.of("p95Ms", 150L, "errorRate", 0.05)), frames);
        assertThat(result.breachedGates()).containsExactlyInAnyOrder("压测响应时间 P95", "压测错误率");
        assertThat(result.conclusion()).contains("200");
    }

    @Test
    void throughputGateUsesBucketWeightedSustainedRateWhenCountsPresent() {
        // Committed floor 900 req/s over two seconds carrying 10+1000 requests: the bucket
        // reading sum(req)/seconds = 505 still breaches (request-count caliber does not
        // exempt throughput), and the gate must be named in the verdict.
        List<PerfMetricFrame> frames = List.of(
                frame(0, 10, 5, 0.0, 10L, 0L, hist(Map.of(5.0, 10L))),
                frame(1, 1000, 5, 0.0, 1000L, 0L, hist(Map.of(5.0, 1000L))));
        PerfAttribution result = judge.judge(
                runWithSla(Map.of("minRps", 900L)), frames);
        assertThat(result.breachedGates()).containsExactly("压测持续吞吐");
    }

    @Test
    void zeroRequestTotalWithBucketsFallsBackToAverageErrorRate() {
        // sum(req)=0 (all-connections-refused / zero-completion shape): the weighted formula
        // has a zero denominator and must fall back to the arithmetic mean instead of NaN.
        List<PerfMetricFrame> frames = List.of(
                frame(0, 0, 0, 1.0, 0L, 0L, null),
                frame(1, 0, 0, 1.0, 0L, 0L, null));
        PerfAttribution result = judge.judge(
                runWithSla(Map.of("errorRate", 0.05)), frames);
        assertThat(result.breachedGates()).containsExactly("压测错误率");
    }
}
