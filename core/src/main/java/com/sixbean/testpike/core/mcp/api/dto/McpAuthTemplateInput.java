package com.sixbean.testpike.core.mcp.api.dto;

import java.util.List;


public record McpAuthTemplateInput(
        String name,
        List<String> tools,
        String remark,
        Integer scope,
        Long projectId) {
}
