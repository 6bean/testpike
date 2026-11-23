package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EF batch : the recall-density floor is decided from ONE source. The
 * doc extraction path owned the arithmetic inline ; the spec-contract,
 * judge question-gen, mock-draft and scenario-enumeration planes are the
 * second..fifth consumers and must not re-copy the formula (R40). This test
 * pins the decision face: hot-key parsing with the conservative fallback,
 * the ceil(expected) rule on the estimated-token face, the floor=0
 * check-off, and the char→token conversion the calibration windows booked
 * the per-domain floors against (char_count x 0.75, never the raw char face).
 * <p>L2 posture: pure decision component, no infrastructure involved.
 */
class RecallDensityGateTest {

    // ---- hot-key parsing: missing/garbage/out-of-range -> domain default ----

    @Test
    void parsesLegalValuesAcrossTheWholeAllowedBand() {
        assertEquals(6.4, RecallDensityGate.parseFloor("6.4", 6.4), 0.0001);
        assertEquals(19.0, RecallDensityGate.parseFloor(" 19.0 ", 20.6), 0.0001);
        assertEquals(50.0, RecallDensityGate.parseFloor("50", 6.4), 0.0001);
        assertEquals(0.0, RecallDensityGate.parseFloor("0", 6.4), 0.0001);
    }

    @Test
    void missingOrGarbageOrOutOfRangeFallsBackToTheDomainDefault() {
        assertEquals(11.8, RecallDensityGate.parseFloor(null, 11.8), 0.0001);
        assertEquals(11.8, RecallDensityGate.parseFloor("   ", 11.8), 0.0001);
        assertEquals(11.8, RecallDensityGate.parseFloor("abc", 11.8), 0.0001);
        assertEquals(11.8, RecallDensityGate.parseFloor("-1", 11.8), 0.0001);
        assertEquals(11.8, RecallDensityGate.parseFloor("51", 11.8), 0.0001);
    }

    // ---- expected item count: ceil(tokens x floor / 1000) ----

    @Test
    void expectedItemsUsesCeilingOnTheEstimatedTokenFace() {
        assertEquals(7, RecallDensityGate.expectedItems(1000, 6.4));
        assertEquals(7, RecallDensityGate.expectedItems(999, 6.4));
        assertEquals(1246, RecallDensityGate.expectedItems(65546, 19.0));
        assertEquals(120, RecallDensityGate.expectedItems(10162, 11.8));
        assertEquals(325, RecallDensityGate.expectedItems(15975, 20.3));
        assertEquals(0, RecallDensityGate.expectedItems(0, 6.4));
        assertEquals(0, RecallDensityGate.expectedItems(1000, 0));
    }

    // ---- the miss decision: floor 0 is check-off, meeting the floor is pass ----

    @Test
    void zeroFloorTurnsTheCheckOffWhateverTheItemCount() {
        assertFalse(RecallDensityGate.missed(0, 1, 100000));
    }

    @Test
    void missOnlyWhenTheItemCountStaysBelowTheFloor() {
        assertTrue(RecallDensityGate.missed(6.4, 6, 1000));
        assertFalse(RecallDensityGate.missed(6.4, 7, 1000));
        assertFalse(RecallDensityGate.missed(6.4, 8, 1000));
    }

    // ---- char→token estimate: the calibration denominator face ----

    @Test
    void estimateTokensConvertsCharsByTheBookedRatio() {
        assertEquals(30000, RecallDensityGate.estimateTokensFromChars(40000));
        assertEquals(1, RecallDensityGate.estimateTokensFromChars(1));
        assertEquals(0, RecallDensityGate.estimateTokensFromChars(0));
    }
}
