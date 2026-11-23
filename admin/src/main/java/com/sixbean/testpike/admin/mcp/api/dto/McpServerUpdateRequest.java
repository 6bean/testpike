package com.sixbean.testpike.admin.mcp.api.dto;


public record McpServerUpdateRequest(
        String name,
        String endpoint,
        Integer transport,
        String auth) {
}
