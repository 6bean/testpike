package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-set drift statistics contract : the drift
 * rate is the share of replayed golden samples whose normalized output digests
 * differ between the control and the candidate template version of the SAME
 * model. Pure math, no DB — this is the statistic the promotion gate weighs
 * against ai.behavior.drift-block-threshold.
 */
class GoldenSetDriftStatisticsTest {

    private static GoldenDriftPair pair(String key, String control, String candidate) {
        return new GoldenDriftPair(key, control, candidate);
    }

    @Test
    void driftedShareIsCountedOverTheFullPairSet() {
        GoldenDriftStat stat = GoldenSetDriftStatistics.analyze(List.of(
                pair("s1", "aaaa", "aaaa"),
                pair("s2", "bbbb", "cccc"),
                pair("s3", "dddd", "dddd"),
                pair("s4", "eeee", "ffff")));
        assertEquals(4, stat.sampleTotal());
        assertEquals(2, stat.driftedCount());
        assertEquals(0, stat.driftPercentPp().compareTo(new java.math.BigDecimal("50.00")));
    }

    @Test
    void digestNormalizationIsCaseInsensitiveAndWhitespaceTolerant() {
        String upper = "A1B2C3".repeat(10) + "AB"; // 62 chars, not a canonical sha
        GoldenDriftStat stat = GoldenSetDriftStatistics.analyze(List.of(
                pair("s1", upper, upper.toLowerCase()),
                pair("s2", " line one\n  line two ", "line one\n line two")));
        assertEquals(0, stat.driftedCount(), "case and inner whitespace must not fake a drift");
        assertEquals(0, stat.driftPercentPp().compareTo(new java.math.BigDecimal("0.00")));
    }

    @Test
    void anEmptyPairSetIsRejectedNotSilentlyZero() {
        // A verdict built on zero samples would make every release "pass" — fail closed.
        assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(null));
    }

    @Test
    void blankSampleIdentityOrBlankOutputIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(List.of(pair(" ", "a", "a"))));
        assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(List.of(pair("s1", "  ", "a"))));
        assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(List.of(pair("s1", "a", null))));
    }

    @Test
    void duplicateSampleKeysAreRejectedBecauseThePackMustBeAnswerableOnce() {
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> GoldenSetDriftStatistics.analyze(List.of(
                        pair("s1", "a", "a"), pair("s1", "b", "c"))));
        assertTrue(bad.getMessage().contains("s1"), "the offending key must be named: " + bad.getMessage());
    }

    @Test
    void percentagesStayInRangeAndRoundToTwoDecimals() {
        GoldenDriftStat stat = GoldenSetDriftStatistics.analyze(List.of(
                pair("s1", "a", "b"), pair("s2", "a", "a"), pair("s3", "a", "a")));
        assertEquals(3, stat.sampleTotal());
        assertEquals(1, stat.driftedCount());
        assertEquals(new java.math.BigDecimal("33.33"), stat.driftPercentPp());
        assertTrue(stat.driftPercentPp().signum() >= 0
                && stat.driftPercentPp().compareTo(new java.math.BigDecimal("100.00")) <= 0);
    }
}
