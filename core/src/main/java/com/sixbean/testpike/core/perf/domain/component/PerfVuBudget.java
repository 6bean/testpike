package com.sixbean.testpike.core.perf.domain.component;

/**
 * VU budget arithmetic for arrival-rate executors :
 * pre-allocated virtual users = ceil(rate x coefficient). Both the k6 script
 * template and the K6Adapter VUSER_EXCLUSIVE segment divisor call this so the
 * pinned divisor and the emitted preAllocatedVUs can never drift apart.
 */
public final class PerfVuBudget {

    private PerfVuBudget() {
    }

    /**
     * Scale a target rate into the virtual-user count to pre-allocate.
     * The coefficient is an integer (K8 reader clamps 1..10), so the product is
     * exact — no rounding needed. A non-positive rate stays 0 (nothing to run).
     *
     * @param rate        arrival rate (RPS) the executor targets
     * @param coefficient K8-resolved vu-per-rps factor (already clamped, >= 1)
     */
    public static int scale(int rate, int coefficient) {
        if (rate <= 0) {
            return 0;
        }
        return rate * Math.max(coefficient, 1);
    }
}
