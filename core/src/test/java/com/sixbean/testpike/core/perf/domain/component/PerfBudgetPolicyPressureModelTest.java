package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-14 budget duration for the four B-2 pressure models .
 * Pure-component test (no Spring): PerfBudgetPolicy.durationSecondsOf is the
 * single duration source consumed by the dispatcher envelope, the launch
 * duration ceiling (A-9), the environment-lock estimate (GA-2) and the
 * terminal data-loss check — a new model without a branch here would silently
 * claim "no budget" and fall back to the global timeout (long-run truncation).
 */
class PerfBudgetPolicyPressureModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode cfg(Map<String, Integer> keys) {
        return MAPPER.valueToTree(keys);
    }

    @Test
    void fixedVuWithDurationClaimsItLoopsOnlyClaimsNothing() {
        // loops-only closed runs have no derivable wall-clock horizon: 0 = no claim
        // (documented fallback), the executor's own iteration bound ends the run.
        assertThat(PerfBudgetPolicy.durationSecondsOf("FIXED_VU",
                cfg(Map.of("vu", 50, "durationSeconds", 900)))).isEqualTo(900);
        assertThat(PerfBudgetPolicy.durationSecondsOf("FIXED_VU",
                cfg(Map.of("vu", 50, "loops", 20)))).isZero();
    }

    @Test
    void ladderClaimsStepSecondsTimesSteps() {
        assertThat(PerfBudgetPolicy.durationSecondsOf("LADDER",
                cfg(Map.of("startRps", 10, "stepRps", 10, "stepSeconds", 60, "steps", 8))))
                .isEqualTo(480);
    }

    @Test
    void businessCurveClaimsDeclaredWindow() {
        assertThat(PerfBudgetPolicy.durationSecondsOf("BUSINESS_CURVE",
                cfg(Map.of("durationSeconds", 86400, "peakRps", 200, "curve9", 80))))
                .isEqualTo(86400);
    }

    @Test
    void soakClaimsHoursAsSeconds() {
        assertThat(PerfBudgetPolicy.durationSecondsOf("SOAK",
                cfg(Map.of("targetRps", 30, "durationHours", 12)))).isEqualTo(43200);
    }
}
