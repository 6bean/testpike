package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateView;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthRequest;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthView;
import com.sixbean.testpike.admin.mcp.app.component.McpAccessGuard;
import com.sixbean.testpike.admin.mcp.infrastructure.remote.client.McpAuthClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
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
@RequestMapping("/api/v1/mcp-auth")
public class McpAuthController {

    private final McpAuthClient mcpAuthClient;
    private final McpAccessGuard mcpGuard;

    public McpAuthController(McpAuthClient mcpAuthClient, McpAccessGuard mcpGuard) {
        this.mcpAuthClient = mcpAuthClient;
        this.mcpGuard = mcpGuard;
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/templates")
    public Response<List<McpAuthTemplateView>> listTemplates() {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.listTemplates());
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/templates")
    public Response<McpAuthTemplateView> createTemplate(@RequestBody McpAuthTemplateRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.create(request));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/templates/{id}")
    public Response<McpAuthTemplateView> updateTemplate(@PathVariable long id,
                                                        @RequestBody McpAuthTemplateRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.update(id, request));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @DeleteMapping("/templates/{id}")
    public Response<Void> deleteTemplate(@PathVariable long id) {
        assertAdmin();
        mcpAuthClient.delete(id);
        return Response.builderSuccess(null);
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/templates/{id}/apply")
    public Response<McpAuthApplyResult> apply(@PathVariable long id,
                                              @RequestBody McpAuthApplyRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.apply(id, request));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/projects/{projectId}")
    public Response<ProjectMcpAuthView> getProjectAuth(@PathVariable long projectId) {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.getProjectAuth(projectId));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/projects/{projectId}")
    public Response<ProjectMcpAuthView> putProjectAuth(@PathVariable long projectId,
                                                       @RequestBody ProjectMcpAuthRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpAuthClient.putProjectAuth(projectId, request));
    }

    private void assertAdmin() {
        CurrentUser user = UserContext.current();
        mcpGuard.assertCanManage(user);
    }
}
