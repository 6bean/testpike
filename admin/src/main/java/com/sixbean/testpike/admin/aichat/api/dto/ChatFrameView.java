package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the core frame-ledger row ({@code ChatFrame}) — admin and
 * core modules share no code, only the JSON wire contract (02 interface design).
 * <p>{@code frameType} is the SSE {@code event:} wire name (lowercase: state /
 * reason / trace / delta / draft / cite / done / error). The core door returns
 * the ledger enum name, so {@code RestCoreAiChatClient} normalises it to the
 * wire name at this boundary — the pump never re-maps ad hoc.
 */
public record ChatFrameView(
        long id,
        long sessionId,
        long messageId,
        long seq,
        String eventType,
        String payload,
        String metaJson) {
}
