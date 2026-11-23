package com.sixbean.testpike.core.mcp.api.dto;

import java.util.List;

/**
 *
 * @param id tp_mcp_auth_template.id
 */
public record McpAuthTemplateInfo(
        Long id,
        String name,
        List<String> tools,
        String remark,
        int scope,
        Long projectId,
        String createdAt,
        String updatedAt) {
}
