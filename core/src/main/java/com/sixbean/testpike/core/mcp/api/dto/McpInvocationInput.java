package com.sixbean.testpike.core.mcp.api.dto;

/**
 *
 * @param masked Outbound desensitization facet : 1 = body masked before
 * sending, 0 = gate off pass-through, null = no outbound body
 * @param apiTokenId presenting API token id (tp_api_token logical reference,
 * no FK; null = caller not authenticated via an API token)
 */
public record McpInvocationInput(
        String caller,
        Long actorId,
        Long taskId,
        Long toolId,
        Long serverId,
        String toolName,
        Integer success,
        Long durationMs,
        String error,
        String digest,
        Integer masked,
        Long apiTokenId) {

    /** Legacy 11-field shape (ai/user callers carry no token dimension). */
    public McpInvocationInput(String caller, Long actorId, Long taskId, Long toolId,
                              Long serverId, String toolName, Integer success,
                              Long durationMs, String error, String digest, Integer masked) {
        this(caller, actorId, taskId, toolId, serverId, toolName, success,
                durationMs, error, digest, masked, null);
    }
}
