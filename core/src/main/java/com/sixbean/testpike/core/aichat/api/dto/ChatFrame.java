package com.sixbean.testpike.core.aichat.api.dto;

import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;

/**
 * One streamed dialog frame as read back from the append-only ledger — the unit
 * a reconnecting client replays (Last-Event-ID → frames with {@code seq} greater
 * than the cursor). {@code seq} is the SSE {@code id:} field, monotonic per
 * session across all messages of the conversation.
 */
public record ChatFrame(
        long id,
        long sessionId,
        long messageId,
        long seq,
        ChatFrameType eventType,
        String payload,
        String metaJson) {
}
