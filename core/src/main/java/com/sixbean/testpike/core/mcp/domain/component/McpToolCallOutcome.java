package com.sixbean.testpike.core.mcp.domain.component;


public record McpToolCallOutcome(
        boolean success,
        String category,
        String message,
        String text) {

    public static McpToolCallOutcome ok(String text) {
        return new McpToolCallOutcome(true, null, null, text);
    }

    public static McpToolCallOutcome fail(String category, String message) {
        return new McpToolCallOutcome(false, category, message, null);
    }
}
