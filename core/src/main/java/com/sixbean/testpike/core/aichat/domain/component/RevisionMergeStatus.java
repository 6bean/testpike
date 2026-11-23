package com.sixbean.testpike.core.aichat.domain.component;

/**
 * Verdict kinds produced by {@link RevisionMergeGate} when a dialog draft
 * proposal lands on a case whose current version drifted past the draft's
 * optimistic anchor (AL/AM batch , ruling 11 of the versioning design):
 * <ul>
 * <li>{@code REBASE} — proposal fields and concurrent edits touch no common
 * field; the write may replay on top of the latest version;</li>
 * <li>{@code CONFLICT} — at least one common field (or a step aligned by
 * stepNo, or a counterpart structural step change) intersects; refuse
 * naming every intersecting item;</li>
 * <li>{@code BASELINE_MISSING} — the proposal's base snapshot (or the latest
 * one) cannot be reconstructed; refuse conservatively, never guess a
 * baseline from the live row.</li>
 * </ul>
 */
public enum RevisionMergeStatus {
    REBASE,
    CONFLICT,
    BASELINE_MISSING
}
