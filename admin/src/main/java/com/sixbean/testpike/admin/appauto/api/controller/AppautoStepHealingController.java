package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoStepHealingController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoStepHealingController(CoreAppautoClient coreAppautoClient,
                                        ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/step-suggestions")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoStepSuggestionView>> list(@RequestParam long projectId,
                                                           @RequestParam long caseId,
                                                           @RequestParam(required = false)
                                                           Integer status,
                                                           @RequestParam(defaultValue = "100")
                                                           int limit) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(
                coreAppautoClient.listStepSuggestions(projectId, caseId, status, limit));
    }

    
    @GetMapping("/step-suggestions/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoStepSuggestionView> get(@PathVariable long id) {
        AppautoStepSuggestionView row = coreAppautoClient.findStepSuggestion(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    
    @PostMapping("/step-suggestions/{id}/adopt")
    @RequirePermission("perm:script:script-edit")
    public Response<AppautoStepSuggestionView> adopt(@PathVariable long id,
                                                      @RequestParam(required = false) Integer
                                                      expectedScriptVersion) {
        AppautoStepSuggestionView row = coreAppautoClient.findStepSuggestion(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        guard.assertCanWriteCase(UserContext.current(), row.projectId());
        return Response.builderSuccess(
                coreAppautoClient.adoptStepSuggestion(id, expectedScriptVersion));
    }

    
    @PostMapping("/step-suggestions/{id}/reject")
    @RequirePermission("perm:script:script-edit")
    public Response<Void> reject(@PathVariable long id) {
        AppautoStepSuggestionView row = coreAppautoClient.findStepSuggestion(id);
        guard.assertCanWriteCase(UserContext.current(), row.projectId());
        coreAppautoClient.rejectStepSuggestion(id);
        return Response.builderSuccess(null);
    }
}
