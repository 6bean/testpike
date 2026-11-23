package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.report.CaliberDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SLA three-gate attribution judge (shared component; D6 caliber single source = the O10
 * CaliberDefinition registry, no second head allowed).
 * <p>The P95 / error-rate / throughput gate logic lands here only — every engine adapter's
 * attribute stage delegates to this component, so the same input yields field-identical
 * verdicts across adapters (engine-native statistics are never trusted; judged at one point).
 * <p>Full-run recomputation has two calibers (one judgment family, picked by raw material,
 * stated honestly in the conclusion line):
 * 1) bucket caliber (frames carry reqCount/failCount/hist) — error rate = sum(fail)/sum(req)
 *    weighted by request count (fixes the equal-weight inflation of low-load seconds),
 *    P95 = merged per-second histograms read at nearest-rank ceil(0.95N) bucket bound
 *    (fixes the unsound max-of-per-second-p95 reading), throughput = sum(req)/sampled seconds;
 * 2) fallback caliber (bucket fields absent = legacy executor frames) — keeps the per-second
 *    readings (worst-second max / arithmetic mean); sum(req)=0 (all connections refused)
 *    also falls back, the divide-by-zero guard comes first.
 */
@Component
public class PerfSlaJudge {

    
    public PerfAttribution judge(Map<String, Object> run, List<PerfMetricFrame> frames) {
        Map<String, Object> sla = asMap(run.get("sla"));
        List<String> breached = new ArrayList<>();
        List<String> details = new ArrayList<>();

        boolean bucketCaliber = frames.stream().anyMatch(f -> f.reqCount() != null);
        long totalReq = frames.stream().mapToLong(f -> f.reqCount() == null ? 0L : f.reqCount()).sum();
        long totalFail = frames.stream().mapToLong(f -> f.failCount() == null ? 0L : f.failCount()).sum();
        long[] mergedHist = mergeHistograms(frames);
        boolean histUsable = PerfHistogram.hasSamples(mergedHist);

        Number p95Limit = asNumber(sla.get("p95Ms"));
        if (p95Limit != null && !frames.isEmpty()) {
            double measured;
            if (histUsable) {
                measured = PerfHistogram.quantile(mergedHist, 0.95);
            } else {
                measured = frames.stream().mapToDouble(PerfMetricFrame::p95Ms).max().orElse(0);
            }
            if (measured > p95Limit.doubleValue()) {
                // Screen discipline : gates are named by their registry
                // display name; the raw code stays machine-side only, never on screen.
                breached.add(CaliberDefinition.PERF_P95.displayName());
                details.add(CaliberDefinition.PERF_P95.displayName()
                        + " 实测 " + Math.round(measured) + "ms 超阈值 " + p95Limit.longValue() + "ms");
            }
        }
        Number errLimit = asNumber(sla.get("errorRate"));
        if (errLimit != null && !frames.isEmpty()) {
            double measured;
            if (bucketCaliber && totalReq > 0) {
                measured = (double) totalFail / totalReq;
            } else {
                measured = frames.stream().mapToDouble(PerfMetricFrame::errorRate).average().orElse(0);
            }
            if (measured > errLimit.doubleValue()) {
                breached.add(CaliberDefinition.PERF_ERROR_RATE.displayName());
                details.add(CaliberDefinition.PERF_ERROR_RATE.displayName() + " 实测 "
                        + Math.round(measured * 10000) / 100.0 + "% 超阈值 "
                        + Math.round(errLimit.doubleValue() * 10000) / 100.0 + "%");
            }
        }
        Number minRps = asNumber(sla.get("minRps"));
        if (minRps != null && !frames.isEmpty()) {
            long sampledSeconds = frames.stream().mapToInt(PerfMetricFrame::secondOffset).distinct().count();
            double sustained;
            if (bucketCaliber && totalReq > 0 && sampledSeconds > 0) {
                sustained = (double) totalReq / sampledSeconds;
            } else {
                sustained = frames.stream().mapToDouble(PerfMetricFrame::rps).average().orElse(0);
            }
            if (sustained < minRps.doubleValue()) {
                breached.add(CaliberDefinition.PERF_THROUGHPUT.displayName());
                details.add(CaliberDefinition.PERF_THROUGHPUT.displayName() + " 实测均速 "
                        + Math.round(sustained) + " req/s 低于承诺 " + minRps.longValue() + " req/s");
            }
        }

        boolean passed = breached.isEmpty();
        // Caliber note wording: plain capability sentences, no registry jargon (O10/D6/codes).
        String caliberNote = histUsable || (bucketCaliber && totalReq > 0)
                ? "口径=全程分桶重算（错误率按请求数加权、P95 取合并直方图最近秩）"
                : "口径=秒窗口回退谱（帧未携带分桶字段或零完成请求，等权秒窗读数如实降级）";
        String conclusion = passed
                ? "SLA 三门禁全部通过（" + caliberNote + "，run " + run.get("id") + "）"
                : "SLA 未通过，越线门禁：" + String.join("；", details)
                        + "（" + caliberNote + "，run " + run.get("id") + "）";
        return new PerfAttribution(passed, conclusion, List.copyOf(breached));
    }

    /** Merge per-frame histograms — delegated to the shared {@link PerfHistogram#merge}
     *  single point (red line 6: the trend read face recomputes with the same math). */
    private static long[] mergeHistograms(List<PerfMetricFrame> frames) {
        return PerfHistogram.merge(frames);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    private static Number asNumber(Object raw) {
        return raw instanceof Number n ? n : null;
    }
}
