package com.sixbean.testpike.admin.aichat.api.dto;

import java.time.LocalDateTime;

/**
 * Admin-side mirror of one dialog history row (AO batch , core
 * {@code ChatSessionListItemInfo}). Lighter than {@link ChatSessionView}: the
 * history panel needs the label and the activity instant, never the frame
 * cursor. {@code messageCount} lets the panel show a thread's depth without a
 * second round-trip to the message door.
 */
public record ChatSessionListItemView(
        long id,
        long projectId,
        String title,
        int source,
        int status,
        LocalDateTime updatedAt,
        long messageCount) {
}
