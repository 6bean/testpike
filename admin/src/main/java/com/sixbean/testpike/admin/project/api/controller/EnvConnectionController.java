package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.ConnectivityTestView;
import com.sixbean.testpike.admin.project.api.dto.EnvConnectionRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvConnectionView;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/projects/{projectId}/env-connections")
public class EnvConnectionController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public EnvConnectionController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<List<EnvConnectionView>> list(@PathVariable long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.listConnections(projectId));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@PathVariable long projectId, @RequestBody EnvConnectionRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.createConnection(projectId, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}")
    public Response<EnvConnectionView> update(@PathVariable long projectId, @PathVariable long id,
                                              @RequestBody EnvConnectionRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.updateConnection(projectId, id, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        coreProjectClient.deleteConnection(projectId, id);
        return Response.builderSuccess();
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{id}/test")
    public Response<ConnectivityTestView> test(@PathVariable long projectId, @PathVariable long id) {
        guard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.testConnection(projectId, id));
    }
}
