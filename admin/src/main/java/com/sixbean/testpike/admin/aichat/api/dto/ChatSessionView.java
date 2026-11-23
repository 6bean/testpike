package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the core session view ({@code ChatSessionInfo}) —
 * returned after opening a conversation so the dock can attach its SSE stream
 * to {@code id} and seed its reconnect cursor from {@code lastSeq}.
 */
public record ChatSessionView(
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
