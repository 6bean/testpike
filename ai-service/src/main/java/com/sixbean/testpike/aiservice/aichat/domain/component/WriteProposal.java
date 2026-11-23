package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * A write proposal the model asked for through the dialog tool loop, captured
 * BEFORE anything is journaled (AO batch stage 2; generalized in the
 * tool-surface clearing batch stage 2).
 * <p>The dialog chain never executes an x-write tool (the dispatcher refuses a
 * token-less call), so the model's call arguments are a proposal, not a
 * result — the orchestrator turns this into a draft card the human confirms
 * through the shared door. One shape for every draft kind: {@code objectType}
 * routes the core-side ledger to the matching write strategy, {@code targetId}
 * is the kind's own anchor (a case id, a tag id...; 0 = create, nothing
 * exists yet) and {@code actionJson} rides through untouched so the strategy
 * re-parses exactly what the human was shown.
 *
 * @param toolName registry name of the call (kept for the audit trail)
 * @param objectType draft kind token from the registry (DraftObjectType wire
 * value); decides which core strategy lands the proposal
 * @param summary human-readable card line shown on the draft card
 * @param targetId existing row the proposal acts on; 0 = create
 * @param baseVersion optimistic-lock anchor the edit was cut against; 0 = none
 * @param actionJson the model's raw argument body (re-parsed at confirm time
 * by the core-side strategy, never rewritten here)
 */
public record WriteProposal(
        String toolName,
        String objectType,
        String summary,
        long targetId,
        int baseVersion,
        String actionJson) {
}
