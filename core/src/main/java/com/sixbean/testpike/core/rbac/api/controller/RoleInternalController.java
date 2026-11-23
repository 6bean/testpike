package com.sixbean.testpike.core.rbac.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.rbac.api.dto.PermGroupInfo;
import com.sixbean.testpike.core.rbac.api.dto.PermInfo;
import com.sixbean.testpike.core.rbac.api.dto.RoleFuncsInput;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput;
import com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput;
import com.sixbean.testpike.core.rbac.api.dto.UserRolesInput;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;


@RestController
@RequestMapping("/internal/roles")
public class RoleInternalController {

    private final RoleService roleService;

    public RoleInternalController(RoleService roleService) {
        this.roleService = roleService;
    }

    
    @GetMapping
    public Response<List<RoleInfo>> roles() {
        return Response.builderSuccess(roleService.roles());
    }

    
    @GetMapping("/{id}")
    public Response<RoleInfo> get(@PathVariable long id) {
        return Response.builderSuccess(roleService.get(id));
    }

    
    @PostMapping
    public Response<Long> create(@RequestBody RoleSaveInput input) {
        return Response.builderSuccess(roleService.create(input));
    }

 
    @PutMapping("/{id}")
    public Response<RoleInfo> update(@PathVariable long id, @RequestBody RoleSaveInput input) {
        return Response.builderSuccess(roleService.update(id, input));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        roleService.delete(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}/funcs")
    public Response<List<String>> funcs(@PathVariable long id) {
        return Response.builderSuccess(roleService.funcsOfRole(id));
    }

 
    @PutMapping("/{id}/funcs")
    public Response<Void> updateFuncs(@PathVariable long id, @RequestBody RoleFuncsInput input) {
        roleService.updateFuncs(id, input.permCodes());
        return Response.builderSuccess(null);
    }

 
    @GetMapping("/users/{userId}/roles")
    public Response<List<RoleInfo>> rolesOfUser(@PathVariable long userId) {
        return Response.builderSuccess(roleService.rolesOfUser(userId));
    }

    
    @PutMapping("/users/{userId}/roles")
    public Response<Void> assignUserRoles(@PathVariable long userId, @RequestBody UserRolesInput input) {
        roleService.assignUserRoles(userId, input.roleIds());
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/perms")
    public Response<List<PermInfo>> permCatalog() {
        return Response.builderSuccess(roleService.permCatalog());
    }

 
    @GetMapping("/perms/catalog")
    public Response<List<PermGroupInfo>> permCatalogGrouped() {
        return Response.builderSuccess(roleService.permCatalogGrouped());
    }

    
    @GetMapping("/{id}/permissions")
    public Response<List<RolePermOverrideView>> permissions(@PathVariable long id) {
        return Response.builderSuccess(roleService.permissionsOfRole(id));
    }

 
    @PutMapping("/{id}/permissions")
    public Response<List<RolePermOverrideView>> savePermissions(@PathVariable long id,
            @RequestBody RolePermissionsInput input) {
        return Response.builderSuccess(roleService.savePermissions(id, input));
    }

    
    @GetMapping("/users/{userId}/perms")
    public Response<Set<String>> permsOfUser(@PathVariable long userId) {
        return Response.builderSuccess(roleService.permsOfUser(userId));
    }

    
    @GetMapping("/users/{userId}/data-scope")
    public Response<Integer> dataScopeOfUser(@PathVariable long userId) {
        return Response.builderSuccess(roleService.dataScopeOfUser(userId));
    }
}
