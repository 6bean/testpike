package com.sixbean.testpike.api.mcp.domain.component;


public record McpToolDefinition(
        String name,
        String description,
        boolean writeTool,
        String requiredScope,
        String inputSchemaJson) {
}
