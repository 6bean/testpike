package com.sixbean.testpike.admin.mcp.api.dto;


public record McpServerCreateRequest(
        String name,
        String endpoint,
        Integer transport,
        String auth) {
}
