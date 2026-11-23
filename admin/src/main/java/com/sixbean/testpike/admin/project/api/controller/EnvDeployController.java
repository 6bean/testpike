package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoView;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/envs")
public class EnvDeployController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public EnvDeployController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{envId}/deploy-info")
    public Response<DeployInfoView> recordDeploy(@PathVariable long envId,
                                                 @RequestBody DeployInfoRequest request) {
        ProjectEnvView env = coreProjectClient.getEnvById(envId);
        guard.assertCanManage(UserContext.current(), env.projectId());
        return Response.builderSuccess(coreProjectClient.recordDeploy(envId, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{envId}/deploy-info")
    public Response<DeployInfoView> getDeployInfo(@PathVariable long envId) {
        ProjectEnvView env = coreProjectClient.getEnvById(envId);
        guard.assertCanView(UserContext.current(), env.projectId());
        return Response.builderSuccess(coreProjectClient.getDeployInfo(envId));
    }
}
