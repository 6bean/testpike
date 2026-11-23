package com.sixbean.testpike.aiservice.mcp.domain.component;


public record McpToolCallResult(
        String toolName,
        long serverId,
        boolean success,
        String text,
        String error,
        long durationMs,
        String digest) {

    public static McpToolCallResult ok(String toolName, long serverId, String text,
                                       long durationMs, String digest) {
        return new McpToolCallResult(toolName, serverId, true, text, null, durationMs, digest);
    }

    public static McpToolCallResult fail(String toolName, long serverId, String error,
                                         long durationMs, String digest) {
        return new McpToolCallResult(toolName, serverId, false, null, error, durationMs, digest);
    }
}
