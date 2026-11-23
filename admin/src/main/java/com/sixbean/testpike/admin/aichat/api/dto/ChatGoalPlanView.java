package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core goal-plan reload fold ({@code ChatGoalPlanInfo}) — the
 * sealed history door carries the session's still-open ledger tail on its
 * newest assistant row so the reloaded page renders the
 * progress card the live stream showed. Admin maps nothing: the shape is the
 * core ledger's own, and the front end folds it through the single live
 * sentence parser rather than inventing a second semantics.
 *
 * @param planVersion ledger identity the tail was read from
 * @param revision change counter of the stored step set (audit only)
 * @param openCount steps still owed (pending or blocked)
 * @param openTitles their titles in stored order
 * @param openStates each open title's own stored state, parallel to
 * openTitles (core ChatGoalPlanInfo mirror)
 * @param openTailSentence the resume-pointer sentence, verbatim frame wording
 */
public record ChatGoalPlanView(
        int planVersion,
        int revision,
        int openCount,
        java.util.List<String> openTitles,
        java.util.List<String> openStates,
        String openTailSentence) {
}
