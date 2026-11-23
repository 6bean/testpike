package com.sixbean.testpike.core.tool.api.dto;

/**
 *
 * @param toolId tp_tool.id
 */
public record ToolTestRunResult(
        Long toolId,
        String toolName,
        boolean success,
        String output,
        String errorCategory,
        Integer errorCode,
        String errorMessage,
        Long durationMs) {

    
    public static ToolTestRunResult ok(Long toolId, String toolName, String output, long durationMs) {
        return new ToolTestRunResult(toolId, toolName, true, output, null, null, null, durationMs);
    }

    
    public static ToolTestRunResult fail(Long toolId, String toolName, String category,
                                         Integer code, String message, long durationMs) {
        return new ToolTestRunResult(toolId, toolName, false, null, category, code, message, durationMs);
    }
}
