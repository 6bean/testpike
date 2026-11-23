package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.EnvHealthView;
import com.sixbean.testpike.admin.project.api.dto.EnvParamView;
import com.sixbean.testpike.admin.project.api.dto.EnvParamsRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvProbeView;
import com.sixbean.testpike.admin.project.api.dto.EnvRequest;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;


import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/projects/{projectId}/envs")
public class ProjectEnvController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public ProjectEnvController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<List<ProjectEnvView>> list(@PathVariable long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.listEnvs(projectId));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@PathVariable long projectId, @RequestBody EnvRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.createEnv(projectId, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{envId}")
    public Response<ProjectEnvView> update(@PathVariable long projectId, @PathVariable long envId,
                                           @RequestBody EnvRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.updateEnv(projectId, envId, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{envId}")
    public Response<Void> delete(@PathVariable long projectId, @PathVariable long envId,
                                 @RequestParam(defaultValue = "false") boolean force) {
        guard.assertCanManage(UserContext.current(), projectId);
        coreProjectClient.deleteEnv(projectId, envId, force);
        return Response.builderSuccess();
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{envId}/health")
    public Response<EnvHealthView> getEnvHealth(@PathVariable long projectId, @PathVariable long envId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.getEnvHealth(projectId, envId));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{envId}/health")
    public Response<EnvProbeView> probeEnvHealth(@PathVariable long projectId, @PathVariable long envId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.probeEnvHealth(projectId, envId));
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{envId}/params")
    public Response<List<EnvParamView>> listParams(@PathVariable long projectId, @PathVariable long envId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.listEnvParams(projectId, envId));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{envId}/params")
    public Response<List<EnvParamView>> saveParams(@PathVariable long projectId, @PathVariable long envId,
                                                   @RequestBody EnvParamsRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.saveEnvParams(projectId, envId, request));
    }
}
