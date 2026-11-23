package com.sixbean.testpike.aiservice.gen.domain.component;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measured-confidence composite rules : the level comes from
 * the equal-weight mean of the signals that actually exist — an absent signal
 * leaves the denominator instead of dragging the score down, and when every
 * signal is absent the measurer returns nothing at all so the caller keeps the
 * model's self-report (never a fabricated measurement).
 * below that = 3 (low). L2 unit stance: pure component, no I/O.
 */
class ConfidenceMeasurerTest {

    private final ConfidenceMeasurer measurer = new ConfidenceMeasurer();

    @Test
    void allSignalsPresentAveragesEqually() {
        Optional<ConfidenceMeasure> out = measurer.measure(1.0, 0.5, 0.25);
        assertTrue(out.isPresent());
        // mean of three signals: (1.0 + 0.5 + 0.25) / 3
        assertEquals(0.5833333, out.get().score(), 1e-6);
    }

    @Test
    void thresholdsMapToLevelsInclusiveAtTheFloor() {
        assertEquals(1, measurer.measure(0.75, null, null).orElseThrow().level());
        assertEquals(2, measurer.measure(0.5, null, null).orElseThrow().level());
        assertEquals(3, measurer.measure(0.49, null, null).orElseThrow().level());
        assertEquals(1, measurer.measure(1.0, 1.0, 1.0).orElseThrow().level());
    }

    @Test
    void absentSignalsLeaveTheDenominator() {
        // fidelity only: 1.0 → level 1 even though the other two never ran.
        assertEquals(1, measurer.measure(1.0, null, null).orElseThrow().level());
        // coverage absent, the other two low → mean of two, still low.
        ConfidenceMeasure low = measurer.measure(0.2, null, 0.1).orElseThrow();
        assertEquals(3, low.level());
        assertTrue(low.low());
    }

    @Test
    void allSignalsAbsentYieldsNothingNotAFabrication() {
        Optional<ConfidenceMeasure> out = measurer.measure(null, null, null);
        assertTrue(out.isEmpty(), "no signal = no measurement: the caller must keep the self-report");
    }

    @Test
    void highAndMidLevelsDoNotFlagLow() {
        assertFalse(measurer.measure(0.9, null, null).orElseThrow().low());
        assertFalse(measurer.measure(0.6, null, null).orElseThrow().low());
        assertTrue(measurer.measure(0.3, 0.4, null).orElseThrow().low());
    }
}
