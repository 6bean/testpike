package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;

import java.util.List;

/**
 * Result of one {@link RevisionMergeGate#assess} call.
 *
 * @param status        verdict kind (never null)
 * @param targetVersion when {@code REBASE}: the row version the caller must
 *                      hand to the shared save chain as the new optimistic
 *                      anchor; null otherwise
 * @param conflictItems human-readable names of every intersecting field/step
 *                      (empty for REBASE; for {@code BASELINE_MISSING} the
 *                      caller composes its own honest refusal sentence)
 * @param mergedSteps   when {@code REBASE} and the proposal touches the step
 *                      region: the overlay-merged step list (proposal edits
 *                      win on the steps the proposal moved, the counterpart's
 *                      newer content is kept on every step the proposal left
 *                      unchanged); null when steps stay untouched or on any
 *                      refusal — the caller must never persist conflictItems
 *                      or partial merges
 */
public record RevisionMergeOutcome(RevisionMergeStatus status, Integer targetVersion,
                                   List<String> conflictItems, List<CaseSnapshotStep> mergedSteps) {

    public RevisionMergeOutcome {
        conflictItems = conflictItems == null ? List.of() : List.copyOf(conflictItems);
        mergedSteps = mergedSteps == null ? null : List.copyOf(mergedSteps);
    }
}
