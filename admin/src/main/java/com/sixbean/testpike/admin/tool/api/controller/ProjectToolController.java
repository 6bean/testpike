package com.sixbean.testpike.admin.tool.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolBindingRequest;
import com.sixbean.testpike.admin.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/projects")
public class ProjectToolController {

    private final ToolClient toolClient;
    private final ToolAccessGuard toolGuard;

    public ProjectToolController(ToolClient toolClient, ToolAccessGuard toolGuard) {
        this.toolClient = toolClient;
        this.toolGuard = toolGuard;
    }

    
    @GetMapping("/{projectId}/tools")
    public Response<ProjectToolView> list(@PathVariable long projectId) {
        toolGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(toolClient.projectTools(projectId));
    }

    
    @PutMapping("/{projectId}/tools")
    public Response<ProjectToolView> save(@PathVariable long projectId,
                                          @RequestBody ProjectToolBindingRequest request) {
        toolGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(toolClient.saveProjectTools(projectId, request));
    }
}
