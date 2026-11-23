package com.sixbean.testpike.admin.mcp.api.dto;

import java.util.Map;

/**
 * Subscription ledger row as the admin face renders it (mirror of the core
 * view record; the bridge never recomputes a column here — every bookkeeping
 * value is what the core service wrote back on the last round).
 *
 * @param serverName            display join against the MCP server table
 * @param lastSyncAt            formatted last round timestamp (core view format)
 * @param lastStatus            updated | unchanged | failed, null before first round
 * @param lastNewCount          entries landed by the last round
 * @param lastSkippedCount      fingerprint-deduplicated entries of the last round
 * @param consecutiveFailures   rounds failed back to back (reset on success)
 */
public record KnowledgeSyncView(
        Long id,
        Long projectId,
        Long serverId,
        String serverName,
        String name,
        String toolName,
        Map<String, Object> paramMapping,
        Integer syncIntervalMinutes,
        Long targetProjectId,
        Long targetPackId,
        boolean autoActivate,
        boolean enabled,
        String lastStatus,
        String lastError,
        String lastSyncAt,
        Integer lastNewCount,
        Integer lastSkippedCount,
        Integer consecutiveFailures) {
}
