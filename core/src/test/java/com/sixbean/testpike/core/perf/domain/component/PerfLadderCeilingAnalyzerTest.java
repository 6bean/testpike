package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CF-1 ceiling-search report tests . A LADDER run is a staircase of
 * nominal rates (startRps + stepRps x i, each stepSeconds long); this component groups
 * the aggregate second frames back into those steps, judges each step with the SAME
 * three gates as the whole-run verdict (PerfSlaJudge single source, D6 no second head),
 * and turns the result into the one-screen conclusion: first breaching step, the
 * SLO-safe watermark (hot-updatable ratio applied to the last passing step), and the
 * knee point found on the second difference of per-step P95.
 */
class PerfLadderCeilingAnalyzerTest {

    /** Config fake that always answers with the built-in default (no K8 override). */
    private static ConfigService defaultOnly() {
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.getValue(Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> call.getArgument(1));
        return configService;
    }

    private static PerfLadderCeilingAnalyzer analyzer() {
        return new PerfLadderCeilingAnalyzer(new PerfSlaJudge(), new PerfConfigReader(defaultOnly()));
    }

    private static PerfLadderCeilingAnalyzer analyzerWith(Map<String, String> overrides) {
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.getValue(Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> overrides.getOrDefault(call.getArgument(0), call.getArgument(1)));
        return new PerfLadderCeilingAnalyzer(new PerfSlaJudge(),
                new PerfConfigReader(configService));
    }

    private static String hist(double bound, long count) {
        double[] bounds = PerfHistogram.boundaries();
        long[] counts = new long[bounds.length];
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i] == bound) {
                counts[i] = count;
            }
        }
        return PerfHistogram.toJson(counts);
    }

    /** One aggregate frame carrying bucket raw material (weighted error rate + merged hist). */
    private static PerfMetricFrame frame(int second, double rps, double p95Ms,
                                         long req, long fail, double histBound, long histCount) {
        return new PerfMetricFrame(second, rps, p95Ms, fail == 0 ? 0.0 : (double) fail / req,
                null, null, null, null, null, req, fail, null, hist(histBound, histCount), null, "");
    }

    /** stepSeconds=10, steps=4, start=100, step=100 -> 40 nominal seconds across 200..500 rps. */
    private static Map<String, Object> ladderConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("startRps", 100);
        config.put("stepRps", 100);
        config.put("stepSeconds", 10);
        config.put("steps", 4);
        return config;
    }

    private static Map<String, Object> runWithSla(Map<String, Object> sla) {
        Map<String, Object> run = new HashMap<>();
        run.put("id", 7L);
        run.put("sla", sla);
        return run;
    }

    /** 40 seconds, healthy load unless flagged otherwise. */
    private static List<PerfMetricFrame> healthyFrames(int from, int to, double p95Bound) {
        List<PerfMetricFrame> frames = new ArrayList<>();
        for (int s = from; s < to; s++) {
            frames.add(frame(s, 300, p95Bound, 300, 0, p95Bound, 300));
        }
        return frames;
    }

    @Test
    void breachingStepFoundAndSafeWatermarkSitsAtEightyPercentBeforeBreach() {
        // Steps: 1=[0,10) @200, 2=[10,20) @300, 3=[20,30) @400, 4=[30,40) @500.
        // Step 3 carries a 10% weighted error rate against the 5% gate -> first breach.
        List<PerfMetricFrame> frames = new ArrayList<>(healthyFrames(0, 20, 50));
        for (int s = 20; s < 30; s++) {
            frames.add(frame(s, 300, 50, 200, 20, 50, 200));
        }
        frames.addAll(healthyFrames(30, 40, 50));
        PerfCeilingFinding finding = analyzer().analyze(
                runWithSla(Map.of("errorRate", 0.05)), ladderConfig(), frames);

        assertThat(finding.applicable()).isTrue();
        assertThat(finding.tiers()).hasSize(4);
        assertThat(finding.breachStep()).isEqualTo(3);
        assertThat(finding.lastPassStep()).isEqualTo(2);
        // Watermark caliber: last passing step's nominal rate x default 0.8 ratio.
        assertThat(finding.safeWatermarkRps()).isEqualTo(300 * 0.8);
        assertThat(finding.conclusion()).contains("400").contains("240");
    }

    @Test
    void caliberNoteClosesWithAFullStopForScreenPunctuationHygiene() {
        // Vision re-score booked one real defect: the caliber footnote
        // rendered as a run-on sentence with no terminal period. User-facing note
        // text must end with a full stop like every other sentence on the page.
        List<PerfMetricFrame> frames = new ArrayList<>(healthyFrames(0, 20, 50));
        for (int s = 20; s < 30; s++) {
            frames.add(frame(s, 300, 50, 200, 20, 50, 200));
        }
        frames.addAll(healthyFrames(30, 40, 50));
        PerfCeilingFinding finding = analyzer().analyze(
                runWithSla(Map.of("errorRate", 0.05)), ladderConfig(), frames);

        assertThat(finding.caliberNote()).endsWith("。");
        assertThat(finding.caliberNote()).contains("口径=台阶名义速率");
    }

    @Test
    void allStepsPassingKeepsTopRateAsWatermarkBase() {
        List<PerfMetricFrame> frames = healthyFrames(0, 40, 50);
        PerfCeilingFinding finding = analyzer().analyze(
                runWithSla(Map.of("errorRate", 0.05)), ladderConfig(), frames);

        assertThat(finding.breachStep()).isNull();
        assertThat(finding.safeWatermarkRps()).isEqualTo(500 * 0.8);
        assertThat(finding.conclusion()).contains("4 级台阶全部通过");
    }

    @Test
    void kneePointMarksWhereLatencyGrowthDoubles() {
        // P95 per step: 50, 55, 60, 300 — the last hop grows 240ms after a 5ms hop,
        // far past the 2x second-difference trigger -> knee at step 4 even though the
        // 1000ms SLA is never breached (knee annotation stays independent of the gates).
        List<PerfMetricFrame> frames = new ArrayList<>();
        double[] p95 = {50, 55, 60, 300};
        for (int step = 0; step < 4; step++) {
            frames.addAll(healthyFrames(step * 10, step * 10 + 10, p95[step]));
        }
        PerfCeilingFinding finding = analyzer().analyze(
                runWithSla(Map.of("p95Ms", 1000L)), ladderConfig(), frames);

        assertThat(finding.breachStep()).isNull();
        assertThat(finding.kneeStep()).isEqualTo(4);
        assertThat(finding.conclusion()).contains("拐点");
    }

    @Test
    void missingLadderKeysDegradeHonestlyWithoutFakeNumbers() {
        List<PerfMetricFrame> frames = healthyFrames(0, 10, 50);
        PerfCeilingFinding finding = analyzer().analyze(
                runWithSla(Map.of("errorRate", 0.05)), Map.of("startRps", 100), frames);

        assertThat(finding.applicable()).isFalse();
        assertThat(finding.safeWatermarkRps()).isNull();
        assertThat(finding.conclusion()).contains("阶梯");
    }

    @Test
    void watermarkRatioIsHotUpdatableThroughConfigKey() {
        List<PerfMetricFrame> frames = healthyFrames(0, 40, 50);
        PerfCeilingFinding finding = analyzerWith(
                Map.of(PerfConfigReader.KEY_CEILING_SAFE_WATERMARK_RATIO, "0.7")).analyze(
                runWithSla(Map.of("errorRate", 0.05)), ladderConfig(), frames);

        assertThat(finding.safeWatermarkRps()).isEqualTo(500 * 0.7);
    }

    @Test
    void dirtyRatioValueFallsBackToDefaultCaliber() {
        List<PerfMetricFrame> frames = healthyFrames(0, 40, 50);
        PerfCeilingFinding finding = analyzerWith(
                Map.of(PerfConfigReader.KEY_CEILING_SAFE_WATERMARK_RATIO, "abc")).analyze(
                runWithSla(Map.of("errorRate", 0.05)), ladderConfig(), frames);

        assertThat(finding.safeWatermarkRps()).isEqualTo(500 * 0.8);
    }
}
