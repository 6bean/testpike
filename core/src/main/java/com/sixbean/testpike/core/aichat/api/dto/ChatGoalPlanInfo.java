package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Goal-plan ledger tail as it reads back for the reloaded dialog view
 * : the live stream folds the open-tail state frame into
 * the collapsible progress card, but sealed history rows do not replay
 * frames through the messages door — so the current ledger of the session
 * is projected onto its newest sealed assistant row, the same posture as
 * the draft-card fold. {@code openTailSentence} is the single-source copy of
 * the orchestrator's frame wording (ChatRunOrchestrator
 * .appendGoalPlanResumePointer, ai-service): the front end recognises the
 * card by that pinned shape and never re-invents a second phrasing.
 * Absent or fully-closed ledgers project nothing (the row carries null).
 *
 * @param planVersion ledger identity the tail was read from
 * @param revision change counter of the stored step set (audit only)
 * @param openCount steps still owed (pending or blocked)
 * @param openTitles their titles in stored order
 * @param openStates each open title's own stored state, parallel to
 * openTitles (pending or blocked) — the reload card
 * renders a blocked step as blocked instead of
 * flattening every open step to pending
 * @param openTailSentence the resume-pointer sentence, verbatim frame wording
 */
public record ChatGoalPlanInfo(
        int planVersion,
        int revision,
        int openCount,
        java.util.List<String> openTitles,
        java.util.List<String> openStates,
        String openTailSentence) {
}
