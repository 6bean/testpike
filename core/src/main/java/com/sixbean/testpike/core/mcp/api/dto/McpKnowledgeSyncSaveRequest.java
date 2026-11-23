package com.sixbean.testpike.core.mcp.api.dto;

import java.util.Map;

/**
 * Save/update payload for one MCP knowledge sync subscription .
 *
 * @param projectId owning project of the subscription
 * @param serverId subscribed MCP server (must exist)
 * @param name subscription name, unique inside the project
 * @param toolName read-only tool picked from the server's connection-test snapshot
 * @param paramMapping tool input parameters applied on every sync round (may be empty)
 * @param syncIntervalMinutes schedule cadence per subscription row (guard range in
 * service); null means "use the hot-updated default" from
 * the ai.mcp.sync.interval-minutes config key
 * @param targetProjectId knowledge landing bucket: 0 = global ledger, >0 = project
 * @param targetPackId optional content pack, 0 = not pack-owned
 * @param autoActivate quality-gate override; null means the factory default (off)
 */
public record McpKnowledgeSyncSaveRequest(
        Long projectId,
        Long serverId,
        String name,
        String toolName,
        Map<String, Object> paramMapping,
        Integer syncIntervalMinutes,
        long targetProjectId,
        long targetPackId,
        Boolean autoActivate) {
}
