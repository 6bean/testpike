package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;

/**
 * <p>steps = CF-8 explainable ladder rows (machine keys + human detail/fixBy/entryPath),
 * rendered by the wizard as a per-door traffic light.
 * <p>runDurationSeconds/budgetMarginMinutes/budgetMs = U-5 pre-launch budget preview,
 * computed server-side by the same PerfBudgetPolicy single source the dispatcher writes
 * (the wizard displays it and never recomputes the hot-updatable margin locally; 0 =
 * the scenario declared no usable duration, consumer falls back to the global timeout).
 */
public record PerfGateDecisionView(boolean passed, String gate, String reason,
                                   List<PerfGateStepView> steps,
                                   long runDurationSeconds, int budgetMarginMinutes, long budgetMs) {
}
