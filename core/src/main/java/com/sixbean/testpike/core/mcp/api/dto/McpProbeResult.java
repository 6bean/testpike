package com.sixbean.testpike.core.mcp.api.dto;


public record McpProbeResult(
        boolean success,
        String category,
        String message,
        McpServerInfo server) {
}
