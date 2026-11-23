package com.sixbean.testpike.aiservice.tool.domain.component;


public record ToolCallOutcome(String toolName, boolean success, String content) {

    public static ToolCallOutcome ok(String toolName, String content) {
        return new ToolCallOutcome(toolName, true, content);
    }

    public static ToolCallOutcome fail(String toolName, String reason) {
        return new ToolCallOutcome(toolName, false, reason);
    }
}
