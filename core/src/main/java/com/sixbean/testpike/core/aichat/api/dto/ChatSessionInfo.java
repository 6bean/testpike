package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Dialog session view (tp_ai_chat_session row). {@code lastSeq} is the session
 * frame cursor — the anchor a reconnecting client compares its Last-Event-ID
 * against; {@code totalTokens}/{@code totalCost} feed the E9 session attribution
 * segment .
 */
public record ChatSessionInfo(
        long id,
        long projectId,
        long userId,
        String title,
        int source,
        String contextJson,
        int status,
        long lastSeq,
        long totalTokens) {
}
