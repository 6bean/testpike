package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 *
 * @param toolId tp_tool.id
 */
public record ToolInvokeResultRemote(
        Long toolId,
        String toolName,
        boolean success,
        String output,
        String errorCategory,
        Integer errorCode,
        String errorMessage,
        Long durationMs) {
}
