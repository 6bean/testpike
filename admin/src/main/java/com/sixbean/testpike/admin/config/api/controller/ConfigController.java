package com.sixbean.testpike.admin.config.api.controller;

import com.sixbean.testpike.admin.config.api.dto.ConfigSaveRequest;
import com.sixbean.testpike.admin.config.api.dto.ConfigView;
import com.sixbean.testpike.admin.config.api.dto.RetentionItemView;
import com.sixbean.testpike.admin.config.infrastructure.remote.client.ConfigClient;
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
@RequestMapping("/api/v1/configs")
public class ConfigController {

    private final ConfigClient configClient;

    public ConfigController(ConfigClient configClient) {
        this.configClient = configClient;
    }

    
    @GetMapping
    @RequirePermission("perm:system:system-config")
    public Response<List<ConfigView>> list(@RequestParam(required = false) String groupName) {
        return Response.builderSuccess(configClient.list(groupName));
    }

    
    @PostMapping
    @RequirePermission("perm:system:system-config")
    public Response<ConfigView> create(@RequestBody ConfigSaveRequest request) {
        return Response.builderSuccess(configClient.create(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:system:system-config")
    public Response<ConfigView> update(@PathVariable long id, @RequestBody ConfigSaveRequest request) {
        return Response.builderSuccess(configClient.update(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:system:system-config")
    public Response<Void> delete(@PathVariable long id) {
        configClient.delete(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/retention/preview")
    @RequirePermission("perm:system:system-config")
    public Response<List<RetentionItemView>> retentionPreview() {
        return Response.builderSuccess(configClient.retentionPreview());
    }

    
    @PostMapping("/retention/run")
    @RequirePermission("perm:system:system-config")
    public Response<List<RetentionItemView>> retentionRun() {
        return Response.builderSuccess(configClient.retentionRun());
    }
}
