package com.sixbean.testpike.admin.mcp.api.dto;

import java.util.List;


public record McpAuthTemplateView(
        Long id,
        String name,
        List<String> tools,
        String remark,
        int scope,
        Long projectId,
        String createdAt,
        String updatedAt) {
}
