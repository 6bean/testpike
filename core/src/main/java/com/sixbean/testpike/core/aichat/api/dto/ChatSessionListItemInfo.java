package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One row of the dialog history list . Deliberately lighter than
 * {@link ChatSessionInfo}: the panel never needs the frame cursor or the token
 * meter, while {@code messageCount} and {@code updatedAt} drive the row label
 * (untitled = the auto-title already lands server-side) and the date grouping
 * the history panel renders .
 */
public record ChatSessionListItemInfo(
        long id,
        long projectId,
        String title,
        int source,
        int status,
        java.time.LocalDateTime updatedAt,
        long messageCount) {
}
