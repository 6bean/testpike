package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Per-token MCP usage roll-up (GA3 observability triad, segment S2b).
 * <p>Computed on the fly from {@code tp_tool_invocation} filtered by the token
 * attribution column; no aggregate table, so the numbers always match the
 * ledger. Legacy rows without a token id are excluded by construction.
 *
 * @param tokenId the API token the numbers belong to
 * @param calls total invocation rows attributed to the token
 * @param failures rows with success = 0
 * @param p95DurationMs 95th percentile of non-null durations in milliseconds
 * (null when the token has no latency sample yet)
 */
public record McpTokenUsageView(
        long tokenId,
        long calls,
        long failures,
        Double p95DurationMs) {
}
