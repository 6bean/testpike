package com.sixbean.testpike.admin.plugin.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginEnableInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginView;
import com.sixbean.testpike.admin.plugin.infrastructure.remote.client.CorePluginClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;


@RestController
@RequestMapping("/api/v1/plugins")
public class PluginAdminController {

    private final CorePluginClient corePluginClient;

    public PluginAdminController(CorePluginClient corePluginClient) {
        this.corePluginClient = corePluginClient;
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @GetMapping
    public Response<List<PluginView>> list(@RequestParam(required = false) String industry,
                                           @RequestParam(required = false) Boolean enabled) {
        return Response.builderSuccess(corePluginClient.list(industry, enabled));
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @PostMapping
    public Response<PluginView> register(@RequestPart("file") MultipartFile file) throws IOException {
        String content;
        try (InputStream in = file.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return Response.builderSuccess(
                corePluginClient.register(UserContext.currentUserId(), content));
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @PutMapping("/{id}/enable")
    public Response<PluginView> setEnabled(@PathVariable long id,
                                           @RequestBody PluginEnableInput input) {
        return Response.builderSuccess(
                corePluginClient.setEnabled(UserContext.currentUserId(), id, input));
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @GetMapping("/{id}")
    public Response<PluginView> detail(@PathVariable long id) {
        return Response.builderSuccess(corePluginClient.detail(UserContext.currentUserId(), id));
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @PutMapping("/{id}")
    public Response<PluginView> update(@PathVariable long id,
                                       @RequestBody PluginUpdateInput input) {
        return Response.builderSuccess(
                corePluginClient.update(UserContext.currentUserId(), id, input));
    }

    
    @RequirePermission("perm:project:plugin-framework")
    @GetMapping("/{id}/applied-projects")
    public Response<List<AppliedProjectView>> appliedProjects(@PathVariable long id) {
        return Response.builderSuccess(
                corePluginClient.appliedProjects(UserContext.currentUserId(), id));
    }
}
