package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateView;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthRequest;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthView;

import java.util.List;


public interface McpAuthClient {

    List<McpAuthTemplateView> listTemplates();

    McpAuthTemplateView create(McpAuthTemplateRequest request);

    McpAuthTemplateView update(long id, McpAuthTemplateRequest request);

    void delete(long id);

    McpAuthApplyResult apply(long id, McpAuthApplyRequest request);

    
    ProjectMcpAuthView getProjectAuth(long projectId);

    ProjectMcpAuthView putProjectAuth(long projectId, ProjectMcpAuthRequest request);
}
