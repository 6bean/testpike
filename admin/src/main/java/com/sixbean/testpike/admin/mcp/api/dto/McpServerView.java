package com.sixbean.testpike.admin.mcp.api.dto;


public record McpServerView(
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
