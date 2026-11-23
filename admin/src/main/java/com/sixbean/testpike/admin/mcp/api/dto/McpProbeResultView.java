package com.sixbean.testpike.admin.mcp.api.dto;


public record McpProbeResultView(
        boolean success,
        String category,
        String message,
        McpServerView server) {
}
