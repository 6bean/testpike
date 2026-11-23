package com.sixbean.testpike.api.mcp.infrastructure.remote;

import java.util.List;

/**
 *
 * @param tokenId presenting token row id (only OK; per-token ledger attribution)
 * @param projectScope GA1 pinned project ids (only OK; empty = unrestricted —
 * the MCP gateway rejects targets outside the pin with a
 * human sentence naming the token's current project set)
 */
public record McpVerifyOutcome(
        boolean ok,
        String status,
        String message,
        Long userId,
        Long tokenId,
        List<String> scopes,
        List<String> projectScope) {

    public static McpVerifyOutcome ok(Long userId, Long tokenId, List<String> scopes,
                                      List<String> projectScope) {
        return new McpVerifyOutcome(true, "OK", null, userId, tokenId, scopes,
                projectScope == null ? List.of() : projectScope);
    }

    /** Pre-GA1 OK shape: no project pin (unrestricted). */
    public static McpVerifyOutcome ok(Long userId, Long tokenId, List<String> scopes) {
        return ok(userId, tokenId, scopes, List.of());
    }

    /** Legacy two-field OK shape (CI gate fixtures without token attribution). */
    public static McpVerifyOutcome ok(Long userId, List<String> scopes) {
        return ok(userId, null, scopes, List.of());
    }

    public static McpVerifyOutcome invalid(String message) {
        return new McpVerifyOutcome(false, "INVALID", message, null, null, List.of(), List.of());
    }

    public static McpVerifyOutcome revoked(String message) {
        return new McpVerifyOutcome(false, "REVOKED", message, null, null, List.of(), List.of());
    }

    public static McpVerifyOutcome expired(String message) {
        return new McpVerifyOutcome(false, "EXPIRED", message, null, null, List.of(), List.of());
    }
}
