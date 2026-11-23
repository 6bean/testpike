package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 * ai-side mirror of the core dialog turn view (tp_ai_chat_message row).
 * <p>Contract mirror of core {@code ChatMessageInfo} — JSON field names align
 * one to one; ai-service never depends on the core module, so the shape is
 * duplicated here by the same rule as {@link ToolInvokeResultRemote}. The
 * status machine is {@code streaming → done | aborted | error}: an aborted
 * turn keeps the partial {@code content} the user already watched (honest
 * degradation, never a pretended completion).
 *
 * @param id tp_ai_chat_message.id
 * @param sessionId owning conversation
 * @param role user / assistant / system
 * @param content answer body (partial on abort, null while streaming)
 * @param reasoning provider thinking text (null when the model emits none)
 * @param status terminal status after close/abort
 * @param seqFrom first ledger seq of the message's frame block
 * @param seqTo last ledger seq (set when the window is sealed)
 * @param promptTokens usage attribution for the E9 ledger
 * @param completionTokens usage attribution for the E9 ledger
 * @param createdAt ledger instant (the dialog view folds a day divider
 * and per-message time from it, )
 * @param feedback per-turn user rating (1 helpful / -1 not helpful /
 * 0 cleared / null never rated) — tail-added mirror
 * field, absent JSON lands null like the other tails
 * @param feedbackReason optional one sentence riding a down rating
 */
public record ChatMessageRemote(
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
        String feedbackReason) {
}
