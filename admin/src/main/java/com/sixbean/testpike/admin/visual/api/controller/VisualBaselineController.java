package com.sixbean.testpike.admin.visual.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.visual.api.dto.VisualBaselineView;
import com.sixbean.testpike.admin.visual.infrastructure.remote.client.VisualBaselineClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/visual-baselines")
public class VisualBaselineController {

    private final VisualBaselineClient visualBaselineClient;
    private final ProjectAccessGuard guard;

    public VisualBaselineController(VisualBaselineClient visualBaselineClient,
                                    ProjectAccessGuard guard) {
        this.visualBaselineClient = visualBaselineClient;
        this.guard = guard;
    }

    
    @GetMapping
    public Response<List<VisualBaselineView>> list(@RequestParam long projectId,
                                                   @RequestParam long caseId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(visualBaselineClient.listByCase(projectId, caseId));
    }

    
    @PostMapping("/{id}/confirm")
    @RequirePermission("perm:script:visual-assert")
    public Response<VisualBaselineView> confirm(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, projectIdOf(id));
        return Response.builderSuccess(visualBaselineClient.confirm(id));
    }

    
    @PostMapping("/{id}/reject")
    @RequirePermission("perm:script:visual-assert")
    public Response<Void> reject(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, projectIdOf(id));
        visualBaselineClient.reject(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}/view-url")
    public Response<String> viewUrl(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectIdOf(id));
        return Response.builderSuccess(visualBaselineClient.viewUrl(id));
    }

    
    private long projectIdOf(long id) {
        return visualBaselineClient.getById(id).projectId();
    }
}
