package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.List;

/**
 * Result of one {@link DeliberationLoop} run (BA batch , re-adjudication
 * section 10 point 1). {@code converged} means the last verification pass returned an
 * empty dispute list; when it did not, {@code residualClaims} carries every
 * still-named disagreement and the caller must surface them on the preview or
 * draft card — passing a non-converged outcome through silently is forbidden
 * by the evidence-first invariant the loop shares with the finish gate.
 *
 * @param draft the final proposal text (revised as far as the rounds
 * allowed)
 * @param residualClaims outstanding disputes after the last pass; empty
 * exactly when converged
 * @param roundsUsed verification passes actually run (early stop reports
 * the round it stopped in)
 * @param modelCalls role calls consumed by the ring — the figure the
 * cost-transparency section multiplies into the batch
 * estimate
 * @param converged true when no claim stayed disputed
 * @param roundsPlanned the round ceiling this run honoured (hot key after
 * clamp) — together with {@code roundsUsed} this is the
 * idle-round hit-rate ledger made visible per call
 * (AY batch the ceiling is a budget, not
 * a quota)
 * @param stalled true when the ring ended because a verification pass
 * named zero new progress (the dispute set repeated
 * verbatim) — the surplus rounds were skipped, the
 * frozen claims surface in {@code residualClaims}
 */
public record DeliberationOutcome(String draft,
                                  List<String> residualClaims,
                                  int roundsUsed,
                                  int modelCalls,
                                  boolean converged,
                                  int roundsPlanned,
                                  boolean stalled) {

    public DeliberationOutcome {
        residualClaims = residualClaims == null ? List.of() : List.copyOf(residualClaims);
    }
}
