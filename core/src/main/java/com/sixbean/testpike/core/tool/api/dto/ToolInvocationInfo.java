package com.sixbean.testpike.core.tool.api.dto;


public record ToolInvocationInfo(
        Long id,
        String caller,
        Long actorId,
        Long taskId,
        Long toolId,
        Long serverId,
        String toolName,
        Integer success,
        Long durationMs,
        String error,
        String digestHash,
        String createdAt) {
}
