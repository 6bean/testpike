package com.sixbean.testpike.admin.credential.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.credential.api.dto.CredentialSaveRequest;
import com.sixbean.testpike.admin.credential.api.dto.CredentialView;
import com.sixbean.testpike.admin.credential.infrastructure.remote.client.CoreCredentialClient;
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
import com.sixbean.testpike.admin.project.api.dto.ProbeOutcomeView;
import com.sixbean.testpike.admin.credential.api.dto.CredentialTestRequest;


@RestController
@RequestMapping("/api/v1/appauto")
public class CredentialController {

    private final CoreCredentialClient coreCredentialClient;
    private final ProjectAccessGuard guard;

    public CredentialController(CoreCredentialClient coreCredentialClient,
                                ProjectAccessGuard guard) {
        this.coreCredentialClient = coreCredentialClient;
        this.guard = guard;
    }

    
    @GetMapping("/credentials")
    @RequirePermission("perm:appauto:cred:view")
    public Response<List<CredentialView>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) Boolean enabledOnly) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreCredentialClient.listCredentials(projectId, enabledOnly));
    }

    
    @GetMapping("/credentials/{id}")
    @RequirePermission("perm:appauto:cred:view")
    public Response<CredentialView> get(@PathVariable long id) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(credential);
    }

    
    @PostMapping("/credentials")
    @RequirePermission("perm:appauto:cred:manage")
    public Response<Long> create(@RequestBody CredentialSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(
                coreCredentialClient.createCredential(request.projectId(), request));
    }

    
    @PutMapping("/credentials/{id}")
    @RequirePermission("perm:appauto:cred:manage")
    public Response<Void> update(@PathVariable long id, @RequestBody CredentialSaveRequest request) {
        CredentialView current = coreCredentialClient.findCredential(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreCredentialClient.updateCredential(id, request);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/credentials/{id}/enabled")
    @RequirePermission("perm:appauto:cred:manage")
    public Response<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        CredentialView current = coreCredentialClient.findCredential(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreCredentialClient.setCredentialEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/credentials/{id}")
    @RequirePermission("perm:appauto:cred:manage")
    public Response<Void> delete(@PathVariable long id) {
        CredentialView current = coreCredentialClient.findCredential(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreCredentialClient.deleteCredential(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/credentials/{id}/test")
    @RequirePermission("perm:appauto:cred:view")
    public Response<ProbeOutcomeView> testNow(@PathVariable long id,
                                              @RequestBody CredentialTestRequest request) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(coreCredentialClient.testCredential(id, request));
    }

    
    @GetMapping("/credentials/{id}/test-events")
    @RequirePermission("perm:appauto:cred:view")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>> testEvents(
            @PathVariable long id, @RequestParam String runToken,
            @RequestParam(defaultValue = "0") long cursor) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(coreCredentialClient.testEvents(id, runToken, cursor));
    }

    /** Run history for the credential-test revisit entry (same view door). */
    @GetMapping("/credentials/{id}/test-runs")
    @RequirePermission("perm:appauto:cred:view")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> testRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(coreCredentialClient.testRuns(id, limit));
    }

    
    @GetMapping("/credentials/{id}/test-events/{eventId}/reveal")
    @RequirePermission("perm:appauto:cred:view")
    public Response<com.sixbean.testpike.admin.project.api.dto.TrailRevealView> revealTrailField(
            @PathVariable long id, @PathVariable long eventId, @RequestParam String field) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(
                coreCredentialClient.revealTestTrailField(id, eventId, field));
    }

    
    @GetMapping("/credentials/{id}/test-events/{eventId}/full")
    @RequirePermission("perm:appauto:cred:view")
    public Response<com.sixbean.testpike.admin.project.api.dto.TrailFullTextView> trailFullText(
            @PathVariable long id, @PathVariable long eventId,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(required = false) Integer limit) {
        CredentialView credential = coreCredentialClient.findCredential(id);
        guard.assertCanView(UserContext.current(), credential.projectId());
        return Response.builderSuccess(
                coreCredentialClient.testTrailFullText(id, eventId, from, limit));
    }
}
