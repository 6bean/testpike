package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Peak-concurrency tier derivation (single source shared by the launch gate
 * and the CF-5 capacity pre-flight). Verbatim contract of the rules moved out
 * of the launch service: each model reads its own key, LADDER computes the
 * search top, and every malformed shape yields null = fail-closed at launch /
 * no-claim on the card. If a future model key is renamed, this test is the
 * drift alarm both consumers share.
 */
class PerfPressureTierTest {

    private final PerfPressureTier tier = new PerfPressureTier(new ObjectMapper());

    @Test
    void constantRateAndSoakReadTargetRps() {
        assertEquals(200, tier.of("CONSTANT_RPS", "{\"targetRps\":200}"));
        assertEquals(50, tier.of("SOAK", "{\"targetRps\":50}"));
    }

    @Test
    void rampSpikeAndBusinessCurveReadPeakRps() {
        assertEquals(300, tier.of("RAMP", "{\"peakRps\":300}"));
        assertEquals(800, tier.of("SPIKE", "{\"peakRps\":800}"));
        assertEquals(120, tier.of("BUSINESS_CURVE", "{\"peakRps\":120}"));
    }

    @Test
    void fixedVuPoolSizeIsTheTier() {
        assertEquals(40, tier.of("FIXED_VU", "{\"vu\":40}"));
    }

    @Test
    void ladderPlansForTheSearchTop() {
        assertEquals(250, tier.of("LADDER",
                "{\"startRps\":50,\"stepRps\":40,\"steps\":5}"));
    }

    @Test
    void malformedShapesClaimNothing() {
        // A declared zero tier parses to 0 (faithful to the moved derivation):
        // fail-closed rejection is the launch gate's own concurrency>0 rule,
        // and the CF-5 card treats tier<=0 as no-claim silence.
        assertEquals(0, tier.of("RAMP", "{\"peakRps\":0}"));
        assertNull(tier.of("RAMP", "{\"notPeak\":\"x\"}"));
        assertNull(tier.of("LADDER", "{\"startRps\":50}"));
        assertNull(tier.of("CONSTANT_RPS", "not-json"));
        assertNull(tier.of(null, null));
    }
}
