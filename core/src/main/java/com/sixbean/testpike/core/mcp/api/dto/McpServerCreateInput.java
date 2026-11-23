package com.sixbean.testpike.core.mcp.api.dto;


public record McpServerCreateInput(
        String name,
        String endpoint,
        Integer transport,
        String auth) {
}
