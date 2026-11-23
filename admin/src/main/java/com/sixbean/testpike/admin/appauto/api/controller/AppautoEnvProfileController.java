package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEnvProfileView;
import com.sixbean.testpike.admin.appauto.api.dto.EnvProfileSaveRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
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
@RequestMapping("/api/v1/appauto")
public class AppautoEnvProfileController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoEnvProfileController(CoreAppautoClient coreAppautoClient,
                                       ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/env-profiles")
    @RequirePermission("perm:appauto:profile:view")
    public Response<List<AppautoEnvProfileView>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false)
                                                      String profileKind) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.listEnvProfiles(projectId, profileKind));
    }

    
    @GetMapping("/env-profiles/{id}")
    @RequirePermission("perm:appauto:profile:view")
    public Response<AppautoEnvProfileView> get(@PathVariable long id) {
        AppautoEnvProfileView profile = coreAppautoClient.findEnvProfile(id);
        guard.assertCanView(UserContext.current(), profile.projectId());
        return Response.builderSuccess(profile);
    }

    
    @PostMapping("/env-profiles")
    @RequirePermission("perm:appauto:profile:manage")
    public Response<Long> create(@RequestBody EnvProfileSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(
                coreAppautoClient.createEnvProfile(request.projectId(), request));
    }

    
    @PutMapping("/env-profiles/{id}")
    @RequirePermission("perm:appauto:profile:manage")
    public Response<Void> update(@PathVariable long id, @RequestBody EnvProfileSaveRequest request) {
        AppautoEnvProfileView current = coreAppautoClient.findEnvProfile(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreAppautoClient.updateEnvProfile(id, request);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/env-profiles/{id}")
    @RequirePermission("perm:appauto:profile:manage")
    public Response<Void> delete(@PathVariable long id) {
        AppautoEnvProfileView current = coreAppautoClient.findEnvProfile(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreAppautoClient.deleteEnvProfile(id);
        return Response.builderSuccess(null);
    }
}
