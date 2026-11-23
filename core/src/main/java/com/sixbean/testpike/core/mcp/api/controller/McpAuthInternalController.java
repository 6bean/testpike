package com.sixbean.testpike.core.mcp.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyInput;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInput;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInfo;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInput;
import com.sixbean.testpike.core.mcp.domain.service.McpAuthTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/mcp-auth")
public class McpAuthInternalController {

    private final McpAuthTemplateService mcpAuthTemplateService;

    public McpAuthInternalController(McpAuthTemplateService mcpAuthTemplateService) {
        this.mcpAuthTemplateService = mcpAuthTemplateService;
    }

    
    @GetMapping("/templates")
    public Response<List<McpAuthTemplateInfo>> listTemplates() {
        return Response.builderSuccess(mcpAuthTemplateService.listTemplates());
    }

    
    @PostMapping("/templates")
    public Response<McpAuthTemplateInfo> create(@RequestBody McpAuthTemplateInput input) {
        return Response.builderSuccess(
                mcpAuthTemplateService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/templates/{id}")
    public Response<McpAuthTemplateInfo> update(@PathVariable long id,
                                                @RequestBody McpAuthTemplateInput input) {
        return Response.builderSuccess(
                mcpAuthTemplateService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @DeleteMapping("/templates/{id}")
    public Response<Void> delete(@PathVariable long id) {
        mcpAuthTemplateService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/templates/{id}/apply")
    public Response<McpAuthApplyResult> apply(@PathVariable long id,
                                              @RequestBody McpAuthApplyInput input) {
        return Response.builderSuccess(
                mcpAuthTemplateService.apply(OperatorContext.currentOperatorId(), id, input));
    }

    
    @GetMapping("/projects/{projectId}")
    public Response<ProjectMcpAuthInfo> getProjectAuth(@PathVariable long projectId) {
        return Response.builderSuccess(mcpAuthTemplateService.getProjectAuth(projectId));
    }

    
    @PutMapping("/projects/{projectId}")
    public Response<ProjectMcpAuthInfo> putProjectAuth(@PathVariable long projectId,
                                                       @RequestBody ProjectMcpAuthInput input) {
        return Response.builderSuccess(
                mcpAuthTemplateService.putProjectAuth(OperatorContext.currentOperatorId(),
                        projectId, input));
    }
}
