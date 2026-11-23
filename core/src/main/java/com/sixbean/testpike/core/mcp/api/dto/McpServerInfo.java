package com.sixbean.testpike.core.mcp.api.dto;


public record McpServerInfo(
        Long id,
        String name,
        String endpoint,
        int transport,
        String transportLabel,
        String authMasked,
        String toolsJson,
        int enabled,
        String enabledLabel,
        int healthStatus,
        String healthLabel,
        String healthMessage,
        String lastProbeAt,
        String createdAt,
        String updatedAt) {
}
