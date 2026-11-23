package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Result of presenting a confirmation reference with a decision (accept or reject).
 *
 * @param domain   landing domain the grant was bound to (e.g. script.writeback)
 * @param decision normalized decision word, accept or reject
 * @param landed   true = the drafted write executed; false = rejected, zero writes
 * @param message  human-readable headline safe to relay into the conversation
 */
public record McpConfirmOutcome(String domain, String decision, boolean landed, String message) {
}
