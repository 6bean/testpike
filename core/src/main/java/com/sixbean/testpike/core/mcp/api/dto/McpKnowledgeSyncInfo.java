package com.sixbean.testpike.core.mcp.api.dto;

import java.util.Map;

/**
 * Subscription view row for the sync ledger face : registration
 * columns plus the bookkeeping columns the scheduler writes back each round.
 *
 * @param serverName display join against tp_mcp_server (the picker label)
 * @param lastSyncAt formatted last round timestamp (ViewFormats baseline)
 * @param lastStatus updated | unchanged | failed, null before first round
 * @param lastNewCount entries landed by the last round
 * @param lastSkippedCount fingerprint-deduplicated entries of the last round
 * @param consecutiveFailures rounds failed back to back (reset on success)
 */
public record McpKnowledgeSyncInfo(
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
