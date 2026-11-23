package com.sixbean.testpike.core.mcp.api.dto;


public record McpServerQuery(
        int page,
        int size,
        String keyword,
        Integer enabled,
        Integer health,
        String sortBy,
        String sortDir) {
}
