package com.sixbean.testpike.core.mcp.api.dto;

/**
 *
 * @param projectId tp_project_mcp_auth.project_id
 */
public record ProjectMcpAuthInfo(
        Long projectId,
        Long templateId,
        String templateName,
        int enabled) {
}
