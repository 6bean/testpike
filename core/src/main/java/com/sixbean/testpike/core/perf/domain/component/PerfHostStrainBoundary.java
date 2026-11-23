package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driver-side strain boundary (B-5 , D3 attribution face).
 * <p>A flattening curve has two candidate explanations: the target hit its own
 * ceiling, or the press box ran out of CPU first. The executor's host samples
 * (tp_perf_metric.host_stats, are the only evidence separating them,
 * and the report must state that evidence honestly:
 * <ul>
 * <li>peak sampled loadRatio above the threshold = "driver-side bottleneck
 * suspected" — the measured ceiling may come from the engine host, not
 * the target (both numbers named in the sentence);</li>
 * <li>samples exist but none exceeds = strain honestly does not hold (the
 * peak value is still named so readers can audit the claim);</li>
 * <li>no usable sample at all = null — the legacy boundary note stands
 * unchanged; fabricating a reading for a pre-self-monitor executor is
 * forbidden (same 0-substitution discipline as every other column).</li>
 * </ul>
 * Pure rule component: the threshold comes from the caller (the K8 key
 * perf.self-monitor.load-ratio is read once at the report face), so the judgment
 * itself never scatters config reads and a second rule set cannot drift in.
 */
@Component
public class PerfHostStrainBoundary {

    private final ObjectMapper objectMapper;

    public PerfHostStrainBoundary(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Aggregate frames of the run + strain ceiling (ratio caliber) -> the
     *  boundary sentence, or null when no frame carries a usable loadRatio.
     *  Corrupt cells are skipped honestly (never a 0-substituted ratio). */
    public String assess(List<PerfMetricFrame> frames, double threshold) {
        Double peak = null;
        for (PerfMetricFrame frame : frames) {
            Double ratio = loadRatioOf(frame.hostStats());
            if (ratio != null && (peak == null || ratio > peak)) {
                peak = ratio;
            }
        }
        if (peak == null) {
            return null;
        }
        if (peak > threshold) {
            return "压测机负载比峰值 " + trim(peak) + " 已超可疑阈值 " + trim(threshold)
                    + "（核均 1 分钟负载/核数）——本报告的红绿灯只判被测上限，但本运行的吞吐平台段"
                    + "驱动侧瓶颈可疑：实测上限可能来自压测机自身饱和而非被测系统，扩容压测机或降档复测后结论才可信";
        }
        return "压测机负载比峰值 " + trim(peak) + " 未超阈值 " + trim(threshold)
                + "——驱动侧瓶颈假设不成立，吞吐平台段可归因于被测上限（红绿灯结论不受压测机饱和干扰）";
    }

    /** loadRatio read from the persisted sample cell (jsonb text or null);
     *  missing field / non-numeric / broken JSON = null = no evidence. */
    private Double loadRatioOf(String hostStatsJson) {
        if (hostStatsJson == null || hostStatsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode ratio = objectMapper.readTree(hostStatsJson).get("loadRatio");
            return ratio != null && ratio.isNumber() ? ratio.doubleValue() : null;
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** Ratio display trimmed of float noise (0.85 not 0.8500001; integers keep no dot). */
    private static String trim(double value) {
        String raw = String.valueOf(value);
        if (raw.endsWith(".0")) {
            return raw.substring(0, raw.length() - 2);
        }
        return raw.length() > 6 ? String.format("%.3f", value) : raw;
    }
}
