package com.sixbean.testpike.aiservice.gen.api.dto;

import java.util.List;

/**
 * Stage0 scenario enumeration outcome . Carries the selection
 * preview the circle-selection step consumes, plus the deliberation-ring
 * accounting (rounds used, model segments burned, convergence verdict and the
 * residual disputes) so the preview can honestly surface non-converged lists
 * instead of silently self-certifying them.
 * <p>{@code expansionEnabled=false} is the legacy shape: the K8 switch was off,
 * zero model calls happened and the caller continues with the old
 * one-case-per-requirement-line path unchanged.
 *
 * @param expansionEnabled whether stage0 expansion ran (K8 switch state)
 * @param scenarios surviving scenario items after the priority filter and cap
 * @param residualClaims disputes still open when the ring stopped (empty = converged)
 * @param roundsUsed deliberation rounds actually run
 * @param modelCalls ring model-call segments counted by the shared loop
 * @param converged true when the final draft verified with zero disputes
 */
public record ScenarioEnumerationResult(
        boolean expansionEnabled,
        List<ScenarioItem> scenarios,
        List<String> residualClaims,
        int roundsUsed,
        int modelCalls,
        boolean converged) {

    /** Legacy pass-through shape when the switch is off (no ring ran at all). */
    public static ScenarioEnumerationResult disabled() {
        return new ScenarioEnumerationResult(false, List.of(), List.of(), 0, 0, true);
    }
}
