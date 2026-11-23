package com.sixbean.testpike.admin.mcp.api.dto;

/**
 * One sync round outcome as the core reports it: status uses the same
 * vocabulary as the ledger column (updated / unchanged / failed) and a failed
 * round always carries the typed reason in {@code error}. The run-now leg
 * keeps answering 200 with this shape for expected failures such as a DOWN
 * probe idle; no exception travels to the drawer.
 */
public record KnowledgeSyncRoundView(
        String status,
        String error,
        int newCount,
        int skippedCount) {
}
