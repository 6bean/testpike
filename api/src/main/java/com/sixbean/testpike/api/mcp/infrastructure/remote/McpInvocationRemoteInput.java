package com.sixbean.testpike.api.mcp.infrastructure.remote;

/**
 *
 * @param apiTokenId presenting token row id (GA3 per-token ledger attribution;
 * field name aligned with core McpInvocationInput JSON)
 */
public record McpInvocationRemoteInput(
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
        Long apiTokenId) {

    /** Legacy 10-field shape (callers without a presenting token). */
    public McpInvocationRemoteInput(String caller, Long actorId, Long taskId, Long toolId,
                                    Long serverId, String toolName, Integer success,
                                    Long durationMs, String error, String digest) {
        this(caller, actorId, taskId, toolId, serverId, toolName, success,
                durationMs, error, digest, null);
    }

    
    public static McpInvocationRemoteInput ok(long actorId, Long apiTokenId, String toolName,
                                              long durationMs, String digest) {
        return new McpInvocationRemoteInput("mcp", actorId, null, null, null,
                toolName, 1, durationMs, null, digest, apiTokenId);
    }

    
    public static McpInvocationRemoteInput fail(long actorId, Long apiTokenId, String toolName,
                                                Long durationMs, String error, String digest) {
        return new McpInvocationRemoteInput("mcp", actorId, null, null, null,
                toolName, 0, durationMs, error, digest, apiTokenId);
    }
}
