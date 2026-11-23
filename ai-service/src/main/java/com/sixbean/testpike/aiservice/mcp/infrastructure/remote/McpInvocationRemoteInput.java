package com.sixbean.testpike.aiservice.mcp.infrastructure.remote;

/**
 * MCP invocation trace write item (G7; element of the POST
 * /internal/mcp-servers/invocations collection — mirrors the core
 * {@code McpInvocationInput} eleven-field contract, same base as
 * SkillUsageRemoteInput; digest carries the <b>raw</b> body and the core
 * service layer normalizes it into a SHA-256 column).
 *
 * @param masked outbound gate facet: 1 = body masked before sending,
 * 0 = gate off pass-through (recorded honestly),
 * null = no outbound body at all (handshake-failure trace)
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
        Integer masked) {

    
    public static McpInvocationRemoteInput ok(Long taskId, long serverId, String toolName,
                                              long durationMs, String digest, int masked) {
        return new McpInvocationRemoteInput("ai", null, taskId, null, serverId,
                toolName, 1, durationMs, null, digest, masked);
    }

    /** Failed-call trace (error = readable text; digest may be null when no
     * response was received; taskId nullable = no task context;
     * masked=null = no outbound body, so the gate facet honestly stays absent). */
    public static McpInvocationRemoteInput fail(Long taskId, long serverId, String toolName,
                                                Long durationMs, String error, String digest,
                                                Integer masked) {
        return new McpInvocationRemoteInput("ai", null, taskId, null, serverId,
                toolName, 0, durationMs, error, digest, masked);
    }

 
    public static McpInvocationRemoteInput bridgeOk(Long taskId, long toolId, long serverId,
                                                    String toolName, long durationMs, String digest,
                                                    int masked) {
        return new McpInvocationRemoteInput("ai", null, taskId, toolId, serverId,
                toolName, 1, durationMs, null, digest, masked);
    }

    
    public static McpInvocationRemoteInput bridgeFail(Long taskId, long toolId, long serverId,
                                                      String toolName, Long durationMs,
                                                      String error, String digest, int masked) {
        return new McpInvocationRemoteInput("ai", null, taskId, toolId, serverId,
                toolName, 0, durationMs, error, digest, masked);
    }
}
