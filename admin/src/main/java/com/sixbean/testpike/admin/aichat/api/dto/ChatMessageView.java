package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core dialog-message view ({@code ChatMessageInfo}) — returned by
 * the abort endpoint so the front end can seal the interrupted turn (partial
 * content kept, honest aborted status; the ledger is the single source).
 * {@code drafts} folds the confirmable cards landed on this message (the
 * sealed-history door carries them so a reloaded page never loses a booked
 * proposal); the abort door returns an empty fold. {@code goalPlan} carries
 * the session's still-open ledger tail on its newest assistant row
 * . {@code credLedger} mirrors the core fold:
 * a turn whose credential-screenshot originals were purged ,
 * so the reloaded panel can render the named placeholder row.
 */
public record ChatMessageView(
        long id,
        long sessionId,
        String role,
        String content,
        String reasoning,
        String status,
        Long seqFrom,
        Long seqTo,
        int promptTokens,
        int completionTokens,
        java.time.LocalDateTime createdAt,
        Integer feedback,
        String feedbackReason,
        java.util.List<ChatDraftView> drafts,
        ChatGoalPlanView goalPlan,
        boolean credLedger) {
}
