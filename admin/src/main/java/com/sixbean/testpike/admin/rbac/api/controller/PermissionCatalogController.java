package com.sixbean.testpike.admin.rbac.api.controller;

import com.sixbean.testpike.admin.rbac.api.dto.PermGroupView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionCatalogController {

    private final CoreRoleClient coreRoleClient;

    public PermissionCatalogController(CoreRoleClient coreRoleClient) {
        this.coreRoleClient = coreRoleClient;
    }

 
    @GetMapping("/catalog")
    @RequirePermission("perm:system:role-manage")
    public Response<List<PermGroupView>> catalog() {
        return Response.builderSuccess(coreRoleClient.permCatalogGrouped());
    }
}
