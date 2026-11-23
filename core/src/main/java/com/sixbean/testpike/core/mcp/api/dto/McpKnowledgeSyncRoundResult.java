package com.sixbean.testpike.core.mcp.api.dto;

/**
 * One subscription sync round outcome .
 *
 * <p>status is the same vocabulary the ledger column uses: updated /
 * unchanged / failed. A failed round always carries the typed reason in
 * {@code error} (probe network/auth/protocol typing or the landing failure)
 * and never propagates an exception into the scheduler loop.
 *
 * <p>newCount / skippedCount mirror the same round's write-back to the
 * ledger columns, so the manual run-now face can render the round result
 * without a second query (package 3b ledger contract).
 */
public record McpKnowledgeSyncRoundResult(
        String status,
        String error,
        int newCount,
        int skippedCount) {
}
