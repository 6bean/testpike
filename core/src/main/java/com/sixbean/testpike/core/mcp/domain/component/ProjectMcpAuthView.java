package com.sixbean.testpike.core.mcp.domain.component;


public record ProjectMcpAuthView(
        Long templateId,
        int enabled,
        String templateToolsJson) {
}
