package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Hour-weights → timed load segments for the business-curve pressure model
 * (B-2, stage b2, single source shared by both engine templates).
 * <p>curve0..curve23 are percentages of peakRps (the save gate keeps every
 * weight inside 0..100 and at least one hour active). A segment is emitted only
 * for active hours inside the declared window: an idle hour is simply absent —
 * a rate-0 segment would fake a paused-but-loaded stage and distort the
 * attribution timeline. The rate floors at 1 so a rounding-to-zero peak can
 * never compile a silent no-load run.
 */
public final class PerfCurveSegments {

    /** One active hour of the business curve. */
    public record Segment(int hour, int rate, int startTimeSeconds, int spanSeconds) {
    }

    private static final int HOUR_SECONDS = 3600;

    private PerfCurveSegments() {
    }

    /**
     * Derive the segment list from a validated pressure config.
     *
     * @param config       pinned pressure config (peakRps/durationSeconds present,
     *                     curve&lt;h&gt; weights 0..100 — all guaranteed by the save chain)
     * @param totalSeconds declared run window; hours beyond it are never emitted
     */
    public static List<Segment> derive(JsonNode config, int totalSeconds) {
        int peak = config.path("peakRps").asInt(0);
        List<Segment> out = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            int from = hour * HOUR_SECONDS;
            if (from >= totalSeconds) {
                break;
            }
            int weight = config.path("curve" + hour).asInt(0);
            if (weight <= 0) {
                continue;
            }
            int rate = Math.max(1, (int) Math.round(peak * weight / 100.0));
            int span = Math.min(HOUR_SECONDS, totalSeconds - from);
            out.add(new Segment(hour, rate, from, span));
        }
        return out;
    }
}
