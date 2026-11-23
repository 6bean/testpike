package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyInput;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInput;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInfo;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInput;

import java.util.List;


public interface McpAuthTemplateService {

    
    List<McpAuthTemplateInfo> listTemplates();

    
    McpAuthTemplateInfo create(Long operatorId, McpAuthTemplateInput input);

    
    McpAuthTemplateInfo update(Long operatorId, Long id, McpAuthTemplateInput input);

    
    void delete(Long operatorId, Long id);

    
    McpAuthApplyResult apply(Long operatorId, Long templateId, McpAuthApplyInput input);

    
    ProjectMcpAuthInfo getProjectAuth(Long projectId);

    
    ProjectMcpAuthInfo putProjectAuth(Long operatorId, Long projectId, ProjectMcpAuthInput input);
}
