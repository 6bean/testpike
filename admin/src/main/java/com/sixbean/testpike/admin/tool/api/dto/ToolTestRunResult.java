package com.sixbean.testpike.admin.tool.api.dto;


public record ToolTestRunResult(
        Long toolId,
        String toolName,
        boolean success,
        String output,
        String errorCategory,
        Integer errorCode,
        String errorMessage,
        Long durationMs) {
}
