package com.sixbean.testpike.admin.plugin.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.admin.plugin.infrastructure.remote.client.CorePluginClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/projects")
public class PluginApplyAdminController {

    private final CorePluginClient corePluginClient;
    private final ProjectAccessGuard projectAccessGuard;

    public PluginApplyAdminController(CorePluginClient corePluginClient,
                                      ProjectAccessGuard projectAccessGuard) {
        this.corePluginClient = corePluginClient;
        this.projectAccessGuard = projectAccessGuard;
    }

    
    @PostMapping("/{projectId}/plugins/{pluginId}/apply")
    public Response<PluginApplyView> apply(@PathVariable long projectId,
                                           @PathVariable long pluginId,
                                           @RequestBody(required = false) PluginApplyInput input) {
        CurrentUser user = UserContext.current();
        projectAccessGuard.assertCanManage(user, projectId);
        boolean confirm = input != null && input.confirm();
        return Response.builderSuccess(
                corePluginClient.apply(user.userId(), projectId, pluginId,
                        new PluginApplyInput(confirm)));
    }

    
    @DeleteMapping("/{projectId}/plugins/{pluginId}")
    public Response<Void> uninstall(@PathVariable long projectId,
                                    @PathVariable long pluginId) {
        CurrentUser user = UserContext.current();
        projectAccessGuard.assertCanManage(user, projectId);
        corePluginClient.uninstall(user.userId(), projectId, pluginId);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{projectId}/plugins")
    public Response<List<AppliedPluginView>> listApplied(@PathVariable long projectId) {
        CurrentUser user = UserContext.current();
        projectAccessGuard.assertCanView(user, projectId);
        return Response.builderSuccess(corePluginClient.listApplied(user.userId(), projectId));
    }
}
