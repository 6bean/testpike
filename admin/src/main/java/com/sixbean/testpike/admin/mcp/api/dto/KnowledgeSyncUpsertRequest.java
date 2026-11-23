package com.sixbean.testpike.admin.mcp.api.dto;

import java.util.Map;

/**
 * Register/update payload for one knowledge sync subscription (mirror of the
 * core save-request record; all guards live in the core service, this DTO is
 * a transport shape only).
 *
 * @param syncIntervalMinutes   null means "use the hot-updated default" from the
 *                              ai.mcp.sync.interval-minutes config key
 * @param targetProjectId       knowledge landing bucket: 0 = global ledger, >0 = project
 * @param targetPackId          optional content pack, 0 = not pack-owned
 * @param autoActivate          quality-gate override; null means the factory default (off)
 */
public record KnowledgeSyncUpsertRequest(
        Long projectId,
        Long serverId,
        String name,
        String toolName,
        Map<String, Object> paramMapping,
        Integer syncIntervalMinutes,
        long targetProjectId,
        long targetPackId,
        Boolean autoActivate) {

    /**
     * Returns a copy bound to the server addressed by the nested path, so a
     * stale serverId carried in a request body can never register a
     * subscription under a different server. Other fields pass through.
     */
    public KnowledgeSyncUpsertRequest withServerScope(long scopedServerId) {
        return new KnowledgeSyncUpsertRequest(projectId, scopedServerId, name, toolName,
                paramMapping, syncIntervalMinutes, targetProjectId, targetPackId, autoActivate);
    }
}
