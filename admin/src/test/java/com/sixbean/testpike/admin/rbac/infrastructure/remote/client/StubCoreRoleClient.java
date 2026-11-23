package com.sixbean.testpike.admin.rbac.infrastructure.remote.client;

import com.sixbean.testpike.admin.rbac.api.dto.PermGroupView;
import com.sixbean.testpike.admin.rbac.api.dto.PermView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleFuncsRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleSaveRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.rbac.api.dto.UserRolesRequest;

import java.util.List;
import java.util.Set;


public class StubCoreRoleClient implements CoreRoleClient {

    private Set<String> perms = Set.of();

    public StubCoreRoleClient perms(Set<String> perms) {
        this.perms = perms;
        return this;
    }

    @Override
    public List<RoleView> roles() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public RoleView getRole(long roleId) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public long createRole(RoleSaveRequest request) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public RoleView updateRole(long roleId, RoleSaveRequest request) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void deleteRole(long roleId) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<String> funcsOfRole(long roleId) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void updateFuncs(long roleId, RoleFuncsRequest request) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<RoleView> rolesOfUser(long userId) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void assignUserRoles(long userId, UserRolesRequest request) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<PermView> permCatalog() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<PermGroupView> permCatalogGrouped() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<RolePermOverrideView> permissionsOfRole(long roleId) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public List<RolePermOverrideView> savePermissions(long roleId, RolePermOverrideRequest request) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Set<String> permsOfUser(long userId) {
        return perms;
    }

    @Override
    public int dataScopeOfUser(long userId) {
        return 2;
    }
}
