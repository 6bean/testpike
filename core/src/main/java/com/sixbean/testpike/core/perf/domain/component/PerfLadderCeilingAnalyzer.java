package com.sixbean.testpike.core.perf.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CF-1 ceiling-search analyzer : turns a LADDER run's aggregate second
 * frames back into their nominal staircase and answers the one question a staircase is
 * run for — how far can the system be pushed.
 * <p>Method: seconds group into steps of stepSeconds (frame offset / stepSeconds, tail
 * seconds beyond the declared staircase are excluded as cooldown); each step is judged
 * with the SAME three SLA gates as the whole-run verdict by delegating to
 * {@link PerfSlaJudge} (D6 single source — no second head, per-step rows and the run
 * verdict can never disagree on the same raw material). From the step ladder:
 * <ul>
 * <li>first breaching step = the ceiling; the SLO-safe watermark = the last passing
 * step's nominal rate x the hot-updatable ratio key (default 0.8 = "back off 20%
 * from proven-good load", the classic headroom rule — never the breaching step);</li>
 * <li>knee point = first step whose latency growth is more than double the previous
 * step's growth (second-difference trigger on the merged-histogram P95, fallback to the
 * worst-second reading) — the onset of non-linear behavior, annotated independently of
 * the gates so a system can be shown degrading before it breaches.</li>
 * </ul>
 * <p>Rates are NOMINAL ladder rates (what the engine aimed at), stated in the caliber
 * note; a malformed/absent ladder declaration degrades honestly (applicable=false +
 * reason) instead of fabricating tiers.
 */
@Component
public class PerfLadderCeilingAnalyzer {

    private final PerfSlaJudge slaJudge;
    private final PerfConfigReader perfConfigReader;

    public PerfLadderCeilingAnalyzer(PerfSlaJudge slaJudge, PerfConfigReader perfConfigReader) {
        this.slaJudge = slaJudge;
        this.perfConfigReader = perfConfigReader;
    }

    /**
     * Analyze one run. {@code pressureConfig} is the pinned snapshot's ladder parameter
     * map (startRps/stepRps/stepSeconds/steps); frames are the aggregate-caliber seconds.
     */
    public PerfCeilingFinding analyze(Map<String, Object> run, Map<String, Object> pressureConfig,
                                      List<PerfMetricFrame> frames) {
        Integer startRps = positiveInt(pressureConfig.get("startRps"));
        Integer stepRps = nonNegativeInt(pressureConfig.get("stepRps"));
        Integer stepSeconds = positiveInt(pressureConfig.get("stepSeconds"));
        Integer steps = positiveInt(pressureConfig.get("steps"));
        if (startRps == null || stepRps == null || stepSeconds == null || steps == null) {
            return PerfCeilingFinding.notApplicable("寻顶分析不适用：本次运行的钉版快照缺少完整阶梯参数"
                    + "（startRps/stepRps/stepSeconds/steps），非阶梯寻量运行或旧快照，如实缺席禁伪造台阶");
        }
        if (frames.isEmpty()) {
            return PerfCeilingFinding.notApplicable("寻顶分析不适用：本次运行尚无已入库秒帧");
        }

        // Group seconds into staircase steps; seconds past the declared staircase
        // (cooldown/tail flush) stay out of the tiers so counts never lie about steps.
        List<List<PerfMetricFrame>> byStep = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            byStep.add(new ArrayList<>());
        }
        for (PerfMetricFrame frame : frames) {
            int stepIndex = frame.secondOffset() / stepSeconds;
            if (stepIndex >= 0 && stepIndex < steps) {
                byStep.get(stepIndex).add(frame);
            }
        }

        List<PerfCeilingFinding.PerfCeilingTier> tiers = new ArrayList<>();
        List<Double> stepP95 = new ArrayList<>();
        Integer breachStep = null;
        Integer lastPassStep = null;
        for (int i = 0; i < byStep.size(); i++) {
            List<PerfMetricFrame> stepFrames = byStep.get(i);
            if (stepFrames.isEmpty()) {
                stepP95.add(Double.NaN);
                continue;
            }
            int stepNumber = i + 1;
            int nominalRps = startRps + stepRps * stepNumber;
            Map<String, Object> stepRun = new HashMap<>();
            stepRun.put("id", run.get("id") + "#" + stepNumber);
            stepRun.put("sla", run.get("sla"));
            // D6 single source: the same three-gate judge on just this step's raw material.
            PerfAttribution stepVerdict = slaJudge.judge(stepRun, stepFrames);
            long requests = stepFrames.stream()
                    .mapToLong(f -> f.reqCount() == null ? 0L : f.reqCount()).sum();
            tiers.add(new PerfCeilingFinding.PerfCeilingTier(stepNumber, nominalRps,
                    stepVerdict.passed(), stepVerdict.breachedGates(), requests));
            stepP95.add(stepP95Of(stepFrames));
            if (!stepVerdict.passed()) {
                if (breachStep == null) {
                    breachStep = stepNumber;
                }
            } else if (breachStep == null) {
                // Watermark base only counts steps BEFORE the first breach — steps that
                // recover later do not push the safe operating point back up.
                lastPassStep = stepNumber;
            }
        }
        if (tiers.isEmpty()) {
            return PerfCeilingFinding.notApplicable("寻顶分析不适用：秒帧偏移全部落在声明台阶之外（数据异常），"
                    + "不猜测台阶结论");
        }

        double ratio = perfConfigReader.ceilingSafeWatermarkRatio();
        Double watermark = null;
        Integer watermarkBaseStep = breachStep == null ? lastPassStep : lastPassStep;
        if (watermarkBaseStep != null) {
            watermark = nominalOf(watermarkBaseStep, startRps, stepRps) * ratio;
        }

        Integer kneeStep = null;
        String kneeDescription = null;
        double[] p95 = new double[stepP95.size()];
        for (int i = 0; i < p95.length; i++) {
            p95[i] = stepP95.get(i);
        }
        double previousGrowth = Double.NaN;
        for (int i = 1; i < p95.length; i++) {
            if (Double.isNaN(p95[i]) || Double.isNaN(p95[i - 1])) {
                previousGrowth = Double.NaN;
                continue;
            }
            double growth = p95[i] - p95[i - 1];
            if (growth > 0 && previousGrowth > 0 && growth > 2 * previousGrowth) {
                kneeStep = i + 1;
                kneeDescription = "第 " + i + "→" + (i + 1) + " 级台阶 P95 增量 "
                        + Math.round(growth) + "ms（前一级增量 " + Math.round(previousGrowth) + "ms）";
                break;
            }
            previousGrowth = growth;
        }

        // Screen discipline: the caliber note is user-facing — plain wording, no jargon.
        String caliberNote = "口径=台阶名义速率（阶梯参数 起始RPS+级差RPS×级数，发起时刻快照钉版）；"
                + "门禁判定=三门禁逐级重算（与全程结论同一判定谱，禁双头）；"
                + "安全水位系数当前 " + trimNumber(ratio) + "。";
        String conclusion;
        if (breachStep != null) {
            conclusion = "寻顶结论：系统在 " + nominalOf(breachStep, startRps, stepRps)
                    + " RPS 台阶首次破线"
                    + (lastPassStep != null ? "，可稳定承载至 "
                            + nominalOf(lastPassStep, startRps, stepRps) + " RPS"
                            : "，首级即破线（无可稳定承载台阶）");
            if (watermark != null) {
                conclusion += "；SLO 安全水位建议 " + trimNumber(watermark)
                        + " RPS（末级通过台阶 × " + trimNumber(ratio) + "）";
            }
        } else {
            conclusion = "寻顶结论：" + tiers.size() + " 级台阶全部通过，最高台阶 "
                    + nominalOf(steps, startRps, stepRps) + " RPS 未见门禁破线";
            if (watermark != null) {
                conclusion += "；SLO 安全水位建议 " + trimNumber(watermark)
                        + " RPS（最高台阶 × " + trimNumber(ratio) + "）";
            }
            conclusion += "（真实天花板可能更高，可上调阶梯顶后复跑）";
        }
        if (kneeStep != null) {
            conclusion += "；时延拐点出现在第 " + kneeStep + " 级（" + kneeDescription
                    + "），建议按拐点而非破线值定容";
        }
        return new PerfCeilingFinding(true, List.copyOf(tiers), breachStep, lastPassStep,
                watermark, kneeStep, kneeDescription, conclusion, caliberNote);
    }

    private static int nominalOf(int step, int startRps, int stepRps) {
        return startRps + stepRps * step;
    }

    /** Whole-step P95 from merged histograms (bucket caliber); worst-second reading when
     *  legacy frames carry no buckets — the same fallback family as the run-level judge. */
    private static double stepP95Of(List<PerfMetricFrame> stepFrames) {
        long[] merged = new long[PerfHistogram.boundaries().length];
        boolean any = false;
        for (PerfMetricFrame f : stepFrames) {
            if (f.hist() == null || f.hist().isBlank()) {
                continue;
            }
            long[] counts = PerfHistogram.fromJson(f.hist());
            for (int i = 0; i < merged.length; i++) {
                merged[i] += counts[i];
            }
            any = true;
        }
        if (any && PerfHistogram.hasSamples(merged)) {
            return PerfHistogram.quantile(merged, 0.95);
        }
        return stepFrames.stream().mapToDouble(PerfMetricFrame::p95Ms).max().orElse(Double.NaN);
    }

    private static String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static Integer positiveInt(Object raw) {
        Integer v = nonNegativeInt(raw);
        return v != null && v > 0 ? v : null;
    }

    private static Integer nonNegativeInt(Object raw) {
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v >= 0 ? v : null;
        }
        if (raw instanceof String s) {
            try {
                int v = Integer.parseInt(s.trim());
                return v >= 0 ? v : null;
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }
}
