package com.sixbean.testpike.admin.rbac.api.controller;

import com.sixbean.testpike.admin.rbac.api.dto.PermView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleFuncsRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleSaveRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.rbac.api.dto.UserRolesRequest;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final CoreRoleClient coreRoleClient;

    public RoleController(CoreRoleClient coreRoleClient) {
        this.coreRoleClient = coreRoleClient;
    }

    
    @GetMapping
    @RequirePermission("perm:system:role-manage")
    public Response<List<RoleView>> list() {
        return Response.builderSuccess(coreRoleClient.roles());
    }

    
    @GetMapping("/perms")
    @RequirePermission("perm:system:role-manage")
    public Response<List<PermView>> permCatalog() {
        return Response.builderSuccess(coreRoleClient.permCatalog());
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:system:role-manage")
    public Response<RoleView> get(@PathVariable long id) {
        return Response.builderSuccess(coreRoleClient.getRole(id));
    }

    
    @PostMapping
    @RequirePermission("perm:system:role-manage")
    public Response<Long> create(@RequestBody RoleSaveRequest request) {
        return Response.builderSuccess(coreRoleClient.createRole(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:system:role-manage")
    public Response<RoleView> update(@PathVariable long id, @RequestBody RoleSaveRequest request) {
        return Response.builderSuccess(coreRoleClient.updateRole(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:system:role-manage")
    public Response<Void> delete(@PathVariable long id) {
        coreRoleClient.deleteRole(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}/funcs")
    @RequirePermission("perm:system:role-manage")
    public Response<List<String>> funcs(@PathVariable long id) {
        return Response.builderSuccess(coreRoleClient.funcsOfRole(id));
    }

    
    @PutMapping("/{id}/funcs")
    @RequirePermission("perm:system:role-manage")
    public Response<Void> updateFuncs(@PathVariable long id, @RequestBody RoleFuncsRequest request) {
        coreRoleClient.updateFuncs(id, request);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}/permissions")
    @RequirePermission("perm:system:role-manage")
    public Response<List<RolePermOverrideView>> permissions(@PathVariable long id) {
        return Response.builderSuccess(coreRoleClient.permissionsOfRole(id));
    }

    
    @PutMapping("/{id}/permissions")
    @RequirePermission("perm:system:role-manage")
    public Response<List<RolePermOverrideView>> savePermissions(@PathVariable long id,
            @RequestBody RolePermOverrideRequest request) {
        return Response.builderSuccess(coreRoleClient.savePermissions(id, request));
    }
}
