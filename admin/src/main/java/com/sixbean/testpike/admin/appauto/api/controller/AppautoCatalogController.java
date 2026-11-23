package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineAvailabilityView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineHealthView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPlatformView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPortabilityView;
import com.sixbean.testpike.admin.appauto.api.dto.EngineManageRequest;
import com.sixbean.testpike.admin.appauto.api.dto.PlatformManageRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoCatalogController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoCatalogController(CoreAppautoClient coreAppautoClient,
                                    ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/engines")
    @RequirePermission("perm:appauto:engine:view")
    public Response<List<AppautoEngineView>> listEngines(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(coreAppautoClient.listEngines(includeDisabled));
    }

    
    @GetMapping("/engines/availability")
    @RequirePermission("perm:appauto:engine:view")
    public Response<List<AppautoEngineAvailabilityView>> listEngineAvailability(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(coreAppautoClient.listEngineAvailability(includeDisabled));
    }

    
    @GetMapping("/engines/health")
    @RequirePermission("perm:appauto:engine:view")
    public Response<List<AppautoEngineHealthView>> listEngineHealth(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(coreAppautoClient.listEngineHealth(includeDisabled));
    }

    
    @GetMapping("/engines/{code}")
    @RequirePermission("perm:appauto:engine:view")
    public Response<AppautoEngineView> getEngine(@PathVariable String code) {
        return Response.builderSuccess(coreAppautoClient.findEngine(code));
    }

    
    @GetMapping("/portability")
    @RequirePermission("perm:appauto:engine:view")
    public Response<AppautoPortabilityView> precheckPortability(
            @RequestParam long projectId, @RequestParam long caseId,
            @RequestParam(required = false) String engineCode) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(
                coreAppautoClient.precheckPortability(projectId, caseId, engineCode));
    }

    
    @GetMapping("/platforms")
    @RequirePermission("perm:appauto:platform:view")
    public Response<List<AppautoPlatformView>> listPlatforms(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(coreAppautoClient.listPlatforms(includeDisabled));
    }

    
    @GetMapping("/platforms/{code}")
    @RequirePermission("perm:appauto:platform:view")
    public Response<AppautoPlatformView> getPlatform(@PathVariable String code) {
        return Response.builderSuccess(coreAppautoClient.findPlatform(code));
    }

    
    @PutMapping("/engines/{code}")
    @RequirePermission("perm:appauto:engine:manage")
    public Response<Void> updateEngine(@PathVariable String code,
                                       @RequestBody EngineManageRequest request) {
        coreAppautoClient.updateEngine(code, request);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/platforms/{code}")
    @RequirePermission("perm:appauto:platform:manage")
    public Response<Void> updatePlatform(@PathVariable String code,
                                         @RequestBody PlatformManageRequest request) {
        coreAppautoClient.updatePlatform(code, request);
        return Response.builderSuccess(null);
    }
}
