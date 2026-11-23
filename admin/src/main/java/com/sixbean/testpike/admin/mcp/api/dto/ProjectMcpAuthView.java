package com.sixbean.testpike.admin.mcp.api.dto;


public record ProjectMcpAuthView(
        Long projectId,
        Long templateId,
        String templateName,
        int enabled) {
}
