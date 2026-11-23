package com.sixbean.testpike.core.perf.domain.component;

import java.util.List;

/**
 * Gate decision of the load-test safety ladder. {@code gate} is the machine key,
 * {@code reason} is the human-readable sentence for UI banners (never truncated).
 * <p>steps = CF-8 explainable ladder: every check of the gate reports its own
 * PASS/FAIL/SKIP state, so a rejected precheck shows the operator the whole door
 * chain (what passed, which door blocks, what is missing, who can fix, entry link)
 * instead of only the first rejection line.
 *
 * @param passed whether the launch is allowed
 * @param gate   machine key of the blocking door (BLOCKED_SEGMENT/GLOBAL_SWITCH/WHITELIST/APPROVAL/TARGET_ENV/ISOLATION_PRECHECK; PASSED when allowed)
 * @param reason human-readable cause (rejected = actionable guidance, allowed = empty)
 * @param steps  full ladder rows in gate order (first FAIL row = the blocking door)
 * @param runDurationSeconds declared run horizon in seconds (U-5 preview; 0 = model not declared)
 * @param budgetMarginMinutes hot-updatable margin actually applied (K8 key perf.budget-margin-minutes; 0 when no horizon)
 * @param budgetMs dispatch-envelope budget = (duration + margin) scaled (same {@code PerfBudgetPolicy}
 *                 single source the dispatcher writes; 0 = "no budget claim", consumers fall back to
 *                 the global timeout key). Rides along rejected decisions too, so the pre-launch
 *                 summary card always shows the honest window instead of a frontend guess.
 */
public record PerfGateDecision(boolean passed, String gate, String reason, List<PerfGateStep> steps,
                               long runDurationSeconds, int budgetMarginMinutes, long budgetMs) {

    public static PerfGateDecision pass(List<PerfGateStep> steps) {
        return new PerfGateDecision(true, "PASSED", "", List.copyOf(steps), 0L, 0, 0L);
    }

    public static PerfGateDecision pass(List<PerfGateStep> steps, long runDurationSeconds,
                                        int budgetMarginMinutes, long budgetMs) {
        return new PerfGateDecision(true, "PASSED", "", List.copyOf(steps),
                runDurationSeconds, budgetMarginMinutes, budgetMs);
    }

    public static PerfGateDecision reject(String gate, String reason, List<PerfGateStep> steps) {
        return new PerfGateDecision(false, gate, reason, List.copyOf(steps), 0L, 0, 0L);
    }

    public static PerfGateDecision reject(String gate, String reason, List<PerfGateStep> steps,
                                         long runDurationSeconds, int budgetMarginMinutes, long budgetMs) {
        return new PerfGateDecision(false, gate, reason, List.copyOf(steps),
                runDurationSeconds, budgetMarginMinutes, budgetMs);
    }
}
