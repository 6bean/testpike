package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host-sample mapping tests (B-5 : the flush-window row carries the
 * executor's own host sample ({@code hostStats}) beside the frames — the load
 * engine was unreadable / a pre-sample executor omits the key (NULL semantics,
 * never a 0-substituted fake), and a well-formed object maps to compact JSON
 * for the tp_perf_metric.host_stats column so the report boundary note can
 * tell "driver-side strain suspected" from a genuine target ceiling honestly.
 * <p>Single mapping point (red-line-6 codec): the finalizer and the partial
 * ingestor read the same key through {@link PerfWireFrameCodec}, so both
 * channels land the sample identically — a drift here would make the live
 * face and the report face disagree about the very same run.
 */
class PerfHostStatsMappingTest {

    private final PerfWireFrameCodec codec =
            new PerfWireFrameCodec(new ObjectMapper());

    /** Wire payload with a host sample riding beside the frames (03 §2.2 shape). */
    private static Map<String, Object> rowWithHostStats(Object hostStats) {
        return Map.of("secondOffset", 3, "rps", 42.0, "p95Ms", 90.0,
                "errorRate", 0.0, "hostStats", hostStats);
    }

    /** Object payload maps to JSON text and survives the read-back round trip. */
    @Test
    void objectHostStatsMapsToJsonCellAndRoundTrips() throws Exception {
        Map<String, Object> sample = Map.of("load1", 3.4, "cores", 4,
                "loadRatio", 0.85, "memAvailMb", 1024.0);

        List<PerfMetricFrame> frames = codec.toFrames(List.of(rowWithHostStats(sample)));

        assertThat(frames).hasSize(1);
        String cell = frames.get(0).hostStats();
        assertThat(cell).isNotNull();
        Map<?, ?> back = new ObjectMapper().readValue(cell, Map.class);
        assertThat(back.get("cores")).isEqualTo(4);
        assertThat(((Number) back.get("loadRatio")).doubleValue())
                .isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** Pre-sample executor shape (key absent) = NULL semantics preserved — the
     *  boundary note degrades to honest absence downstream, never a guess. */
    @Test
    void absentHostStatsKeyStaysNull() {
        List<PerfMetricFrame> frames = codec.toFrames(List.of(
                Map.of("secondOffset", 0, "rps", 10.0, "p95Ms", 50.0, "errorRate", 0.0)));

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).hostStats()).isNull();
    }

    /** Explicit null payload = NULL cell (same fallback posture as the hist/errorClass
     *  buckets — the codec never invents a sample to fill the key). */
    @Test
    void explicitNullHostStatsPayloadFallsBackToNull() {
        Map<String, Object> row = new java.util.HashMap<>(Map.of(
                "secondOffset", 0, "rps", 10.0, "p95Ms", 50.0, "errorRate", 0.0));
        row.put("hostStats", null);

        List<PerfMetricFrame> frames = codec.toFrames(List.of(row));

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).hostStats()).isNull();
    }

    /** Row-level sample (the §2.2 shape actually shipped: one reading beside the
     *  frames) lands on every frame of the flush window, while a frame already
     *  carrying its own per-frame reading keeps it (explicit beats adopted). */
    @Test
    void attachRowHostSampleLandsOnFramesWithoutTheirOwnReading() {
        List<PerfMetricFrame> frames = codec.toFrames(List.of(
                Map.of("secondOffset", 0, "rps", 10.0, "p95Ms", 50.0, "errorRate", 0.0),
                rowWithHostStats(Map.of("load1", 1.0, "cores", 2,
                        "loadRatio", 0.5, "memAvailMb", 512.0))));

        List<PerfMetricFrame> attached = codec.attachRowHostSample(frames,
                Map.of("load1", 6.0, "cores", 4, "loadRatio", 1.5, "memAvailMb", 2048.0));

        assertThat(attached).hasSize(2);
        assertThat(attached.get(0).hostStats()).contains("\"loadRatio\":1.5");
        assertThat(attached.get(1).hostStats()).contains("\"loadRatio\":0.5");
    }

    /** No row sample = frames untouched (legacy executors keep shipping the pre-B-5
     *  shape; the boundary note downstream reads the NULL as honest absence). */
    @Test
    void attachRowHostSampleWithoutSampleKeepsFramesUntouched() {
        List<PerfMetricFrame> frames = codec.toFrames(List.of(
                Map.of("secondOffset", 0, "rps", 10.0, "p95Ms", 50.0, "errorRate", 0.0)));

        assertThat(codec.attachRowHostSample(frames, null).get(0).hostStats()).isNull();
    }
}
