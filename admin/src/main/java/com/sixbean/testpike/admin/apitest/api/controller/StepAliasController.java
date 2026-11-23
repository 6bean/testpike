package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.StepAliasSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasView;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreStepAliasClient;
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
@RequestMapping("/api/v1/apitest/step-aliases")
public class StepAliasController {

    private final CoreStepAliasClient coreStepAliasClient;
    private final ProjectAccessGuard guard;

    public StepAliasController(CoreStepAliasClient coreStepAliasClient,
                               ProjectAccessGuard guard) {
        this.coreStepAliasClient = coreStepAliasClient;
        this.guard = guard;
    }

    
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<List<StepAliasView>> list(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreStepAliasClient.listAliases(projectId));
    }

    
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<StepAliasView> create(@RequestBody StepAliasSaveRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreStepAliasClient.createAlias(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<StepAliasView> update(@PathVariable long id,
                                          @RequestBody StepAliasUpdateRequest request) {
        StepAliasView current = coreStepAliasClient.findAlias(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreStepAliasClient.updateAlias(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        StepAliasView current = coreStepAliasClient.findAlias(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        coreStepAliasClient.deleteAlias(id);
        return Response.builderSuccess(null);
    }
}
