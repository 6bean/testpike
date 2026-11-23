package com.sixbean.testpike.admin.storage.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.storage.api.dto.StorageConfigSaveRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageConfigView;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeResult;
import com.sixbean.testpike.admin.storage.infrastructure.remote.client.StorageConfigClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/storage")
public class StorageConfigController {

    private final StorageConfigClient storageConfigClient;

    public StorageConfigController(StorageConfigClient storageConfigClient) {
        this.storageConfigClient = storageConfigClient;
    }

    
    @GetMapping("/config")
    @RequirePermission("perm:system:system-config")
    public Response<StorageConfigView> get() {
        return Response.builderSuccess(storageConfigClient.get());
    }

    
    @PutMapping("/config")
    @RequirePermission("perm:system:system-config")
    public Response<StorageConfigView> update(@RequestBody StorageConfigSaveRequest request) {
        return Response.builderSuccess(storageConfigClient.update(request));
    }

    
    @PostMapping("/probe")
    @RequirePermission("perm:system:system-config")
    public Response<StorageProbeResult> probe(@RequestBody StorageProbeRequest request) {
        return Response.builderSuccess(storageConfigClient.probe(request));
    }
}
