package com.sixbean.testpike.core.perf.domain.component;

/**
 *
 * @param reqCount Completed requests this second and step; raw material for weighted
 * error rate and full-run percentile recomputation. NULL = legacy frame
 * without buckets — the judge falls back to the per-second caliber.
 * @param failCount Failed requests this second and step (numerator of the weighted error
 * rate); always &lt;= reqCount, value-range guard sits at the ingestion face.
 * @param durationSumMs Summed response time this second and step (ms); whole-run mean =
 * sum(durationSum)/sum(req) supported by this column.
 * @param hist Latency histogram bucket counts (JSON array; boundaries shared with
 * {@link PerfHistogram#boundaries}; null = no buckets).
 * @param errorClass Error-class counts (JSON object {timeout,refused,http4xx,http5xx,biz};
 * raw material for the TopN error drawer; null = not collected).
 * @param stepKey Step key ('' = un-split frame, the legacy shape); multiple step frames
 * in the same second stay distinguishable through it.
 * @param hostStats Host sample of the flush window that shipped this frame (B-5
 * self-monitor: JSON text {load1,cores,loadRatio,memAvailMb});
 * null = executor predating the sample or unreadable host — the
 * report boundary note degrades to the honest absence, never a
 * fabricated reading (0-substitution forbidden).
 */
public record PerfMetricFrame(int secondOffset, double rps, double p95Ms, double errorRate,
                              Double ttftMs, Double itlMs, Double tokensPerSec,
                              Double authMs, Double authCount,
                              Long reqCount, Long failCount, Double durationSumMs,
                              String hist, String errorClass, String stepKey,
                              String hostStats) {

    /** Compatibility constructor: bucket fields absent = the legacy nine-key payload shape
     *  (executors shipped before the partial-frame step ingest unchanged; the judge
     *  degrades to the fallback caliber for them). */
    public PerfMetricFrame(int secondOffset, double rps, double p95Ms, double errorRate,
                           Double ttftMs, Double itlMs, Double tokensPerSec,
                           Double authMs, Double authCount) {
        this(secondOffset, rps, p95Ms, errorRate, ttftMs, itlMs, tokensPerSec,
                authMs, authCount, null, null, null, null, null, "", null);
    }

    /** Compatibility constructor: the B-5 host sample absent = the pre-self-monitor
 * fifteen-key shape (call sites predating keep compiling; the boundary
 * note reads the NULL as honest absence). */
    public PerfMetricFrame(int secondOffset, double rps, double p95Ms, double errorRate,
                           Double ttftMs, Double itlMs, Double tokensPerSec,
                           Double authMs, Double authCount,
                           Long reqCount, Long failCount, Double durationSumMs,
                           String hist, String errorClass, String stepKey) {
        this(secondOffset, rps, p95Ms, errorRate, ttftMs, itlMs, tokensPerSec,
                authMs, authCount, reqCount, failCount, durationSumMs,
                hist, errorClass, stepKey, null);
    }

    /** Adopt the flush window's host sample (03 §2.2 row-level hostStats key): the
     *  executor samples once per due window and every frame shipped in that window
     *  carries the same reading. A frame that already declares its own sample keeps
     *  it (per-frame payload wins); a null sample leaves the frame untouched. */
    public PerfMetricFrame withHostStatsIfAbsent(String sample) {
        if (sample == null || hostStats != null) {
            return this;
        }
        return new PerfMetricFrame(secondOffset, rps, p95Ms, errorRate,
                ttftMs, itlMs, tokensPerSec, authMs, authCount,
                reqCount, failCount, durationSumMs, hist, errorClass, stepKey, sample);
    }
}
