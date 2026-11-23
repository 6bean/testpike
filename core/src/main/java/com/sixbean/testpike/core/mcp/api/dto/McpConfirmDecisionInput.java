package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Request body of the in-session confirmation door (mcp.review.confirm).
 *
 * @param confirmRef one-shot reference returned by a write tool receipt
 * @param decision   accept = execute the drafted write; reject = cancel it with zero changes
 * @param reason     free-text justification for the audit trail (nullable)
 */
public record McpConfirmDecisionInput(String confirmRef, String decision, String reason) {
}
