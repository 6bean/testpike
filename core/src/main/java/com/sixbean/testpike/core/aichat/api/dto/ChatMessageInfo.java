package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One dialog turn view (tp_ai_chat_message row). The status machine is
 * {@code streaming → done | aborted | error}: abort keeps the partial
 * {@code content} the user already watched (never pretend completion, never
 * throw generated text away); {@code seqFrom}/{@code seqTo} bound the message's
 * frame block, which is the replay window. {@code createdAt} is the ledger
 * instant the row was written — the dialog view folds it into a day divider
 * and a per-message time label .
 * {@code feedback} is the per-turn 👍/👎 observation signal (1 helpful, -1 not
 * helpful, 0 cleared, null never rated) with its optional one-sentence
 * {@code feedbackReason} — the intake face of the self-evolution loop, a read
 * signal for proposals, never a mutation of the answer itself.
 * {@code drafts} folds the confirmable cards landed on this message from the
 * draft ledger (self-evolution closed-loop visibility): sealed history without
 * the fold would book a proposal the reloaded page can never show or confirm.
 * {@code goalPlan} projects the session's still-open goal-plan ledger onto its
 * newest sealed assistant row so the progress card survives a
 * reload — null everywhere else, never a fabricated tail.
 * {@code credLedger} marks a turn that booked credential screenshots whose
 * original pixels were purged at the terminal state ( piece E, D2 =
 * delete + placeholder): the reloaded panel then renders a named placeholder
 * row for that turn instead of the image. Folded core-side from the frames'
 * own marker — the front end never re-reads frame meta.
 */
public record ChatMessageInfo(
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
        java.util.List<ChatDraftInfo> drafts,
        ChatGoalPlanInfo goalPlan,
        boolean credLedger) {
}
