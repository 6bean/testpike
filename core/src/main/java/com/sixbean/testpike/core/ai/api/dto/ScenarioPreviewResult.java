package com.sixbean.testpike.core.ai.api.dto;

import java.util.List;

/**
 * Outcome of one stage0 scenario enumeration as returned by ai-service
 * . Carries the surviving scenario list plus the kernel
 * deliberation-ring accounting so the persisted preview can surface a
 * non-converged list honestly instead of self-certifying it.
 *
 * @param expansionEnabled false = the K8 switch was off: zero model calls and
 * the caller continues with the legacy one-case-per-line
 * path; nothing is written to the preview ledger
 * @param scenarios surviving scenario items after priority filter and cap
 * @param residualClaims disputes still open when the ring stopped
 * @param roundsUsed deliberation rounds actually run
 * @param modelCalls ring model-call segments counted by the shared loop
 * @param converged true when the final draft verified with zero disputes
 */
public record ScenarioPreviewResult(boolean expansionEnabled,
                                    List<ScenarioPreviewItem> scenarios,
                                    List<String> residualClaims,
                                    int roundsUsed,
                                    int modelCalls,
                                    boolean converged) {

    /** Legacy pass-through shape when the switch is off (no ring ran at all). */
    public static ScenarioPreviewResult disabled() {
        return new ScenarioPreviewResult(false, List.of(), List.of(), 0, 0, true);
    }
}
