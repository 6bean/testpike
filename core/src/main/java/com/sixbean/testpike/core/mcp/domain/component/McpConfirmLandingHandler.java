package com.sixbean.testpike.core.mcp.domain.component;

/**
 * Strategy seam between the confirmation loop and one write channel's landing path.
 * <p>Each MCP write domain registers exactly one handler; the settlement service
 * refuses to issue a grant for any domain without a handler, so a gate that cannot
 * settle never exists (fail-closed at the creation point, not at decision time).
 */
public interface McpConfirmLandingHandler {

    /** Domain literal this handler settles (matches {@code script.writeback} style tool names). */
    String domain();

    /**
     * Execute the drafted write verbatim. Throws the channel's own human-readable
     * refusals (approved asset, stale version, ...) when the state moved under the draft.
     */
    void land(McpConfirmEnvelope envelope) throws Exception;
}
