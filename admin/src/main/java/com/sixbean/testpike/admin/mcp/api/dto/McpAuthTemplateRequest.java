package com.sixbean.testpike.admin.mcp.api.dto;

import java.util.List;


public record McpAuthTemplateRequest(
        String name,
        List<String> tools,
        String remark,
        Integer scope,
        Long projectId) {
}
