package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Driver-side strain boundary tests (B-5 , D3 attribution face): the
 * report reads the persisted host samples of the aggregate frames and tells
 * honestly whether the press box itself was saturated — a flattening curve
 * under host strain is "driver bottleneck suspected", never silently attributed
 * to the target. Frames without samples (legacy executor / unreadable proc) =
 * the honest absence: no segment is appended, no 0-substituted reading is
 * fabricated. The threshold is caller-supplied (the K8 key is read once at the
 * report face — this component stays a pure rule, single-source judgment).
 */
class PerfHostStrainBoundaryTest {

    private final PerfHostStrainBoundary boundary =
            new PerfHostStrainBoundary(new ObjectMapper());

    /** Aggregate frame carrying a host sample of the given load ratio. */
    private static PerfMetricFrame frameWithRatio(double ratio) {
        return new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null,
                40L, 0L, 4800.0, null, null, "",
                "{\"load1\": " + (ratio * 4) + ", \"cores\": 4, \"loadRatio\": "
                        + ratio + ", \"memAvailMb\": 1024.0}");
    }

    private static PerfMetricFrame frameWithoutSample() {
        return new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, null, null);
    }

    /** Peak ratio above the threshold = driver-side bottleneck suspected, with the
     *  measured value and the ceiling named in the sentence. */
    @Test
    void peakRatioAboveThresholdMarksDriverStrainSuspected() {
        String note = boundary.assess(List.of(frameWithRatio(0.4), frameWithRatio(2.125)), 0.85);

        assertThat(note).isNotNull()
                .contains("2.125").contains("0.85").contains("驱动侧瓶颈可疑");
    }

    /** Every sampled ratio below the threshold = the strain hypothesis honestly
     *  does not hold (named values ride the sentence, no vague reassurance). */
    @Test
    void peakRatioBelowThresholdMarksStrainNotHolding() {
        String note = boundary.assess(List.of(frameWithRatio(0.4), frameWithRatio(0.6)), 0.85);

        assertThat(note).isNotNull()
                .contains("0.6").contains("0.85").contains("未超");
        assertThat(note).doesNotContain("可疑");
    }

    /** No host samples at all = honest absence (null) — the report keeps the legacy
     *  boundary note unchanged instead of claiming a reading it never received. */
    @Test
    void framesWithoutAnySampleYieldHonestAbsence() {
        assertThat(boundary.assess(List.of(frameWithoutSample()), 0.85)).isNull();
        assertThat(boundary.assess(List.of(), 0.85)).isNull();
    }

    /** A corrupt cell that slipped past the jsonb guard is skipped honestly: good
     *  samples still decide, the broken row never fabricates a ratio. */
    @Test
    void corruptSampleCellSkippedWhileGoodSamplesStillDecide() {
        PerfMetricFrame corrupt = new PerfMetricFrame(1, 50.0, 130.0, 0.03,
                null, null, null, null, null, 50L, 0L, 5000.0, null, null, "",
                "{broken-json");

        String note = boundary.assess(List.of(corrupt, frameWithRatio(1.5)), 0.85);

        assertThat(note).isNotNull().contains("1.5").contains("驱动侧瓶颈可疑");
    }

    /** Sample without a usable loadRatio field (dirty payload) = no evidence, honest
     *  absence rather than a divide-by-zero or a 0-substituted reading. */
    @Test
    void sampleWithoutLoadRatioFieldCountsAsNoEvidence() {
        PerfMetricFrame noRatio = new PerfMetricFrame(2, 50.0, 130.0, 0.0,
                null, null, null, null, null, 50L, 0L, 5000.0, null, null, "",
                "{\"cores\": 8}");

        assertThat(boundary.assess(List.of(noRatio), 0.85)).isNull();
    }
}
