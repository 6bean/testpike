package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Receipt of an issued in-session confirmation grant (MCP write gate).
 * <p>The reference is one-shot: the first successful {@code confirm} presents burn it
 * (atomic GETDEL), so the session channel and the UI draft-card channel race on the
 * same queue with first-wins semantics and no double confirmation.
 *
 * @param confirmRef       random one-shot reference (SecureRandom 32 bytes hex)
 * @param summary          three-part plain-language digest (what object / what change /
 *                         what it affects) returned to the conversation verbatim
 * @param expiresInSeconds validity window in effect at issuance (hot key read live)
 */
public record McpConfirmGrantTicket(String confirmRef, String summary, long expiresInSeconds) {
}
