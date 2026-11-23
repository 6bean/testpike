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


public interface CoreRoleClient {

    
    List<RoleView> roles();

    
    RoleView getRole(long roleId);

    
    long createRole(RoleSaveRequest request);

 
    RoleView updateRole(long roleId, RoleSaveRequest request);

    
    void deleteRole(long roleId);

    
    List<String> funcsOfRole(long roleId);

 
    void updateFuncs(long roleId, RoleFuncsRequest request);

 
    List<RoleView> rolesOfUser(long userId);

    
    void assignUserRoles(long userId, UserRolesRequest request);

    
    List<PermView> permCatalog();

 
    List<PermGroupView> permCatalogGrouped();

    
    List<RolePermOverrideView> permissionsOfRole(long roleId);

    
    List<RolePermOverrideView> savePermissions(long roleId, RolePermOverrideRequest request);

    
    Set<String> permsOfUser(long userId);

    
    int dataScopeOfUser(long userId);
}
