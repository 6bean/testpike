package com.sixbean.testpike.core.rbac.domain.service;

import com.sixbean.testpike.core.rbac.api.dto.PermGroupInfo;
import com.sixbean.testpike.core.rbac.api.dto.PermInfo;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput;
import com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput;

import java.util.List;
import java.util.Set;


public interface RoleService {

    
    List<RoleInfo> roles();

    
    RoleInfo get(long roleId);

    
    long create(RoleSaveInput input);

 
    RoleInfo update(long roleId, RoleSaveInput input);

    
    void delete(long roleId);

    
    List<String> funcsOfRole(long roleId);

 
    void updateFuncs(long roleId, List<String> permCodes);

 
    List<RoleInfo> rolesOfUser(long userId);

    
    void assignUserRoles(long userId, List<Long> roleIds);

 
    Set<String> permsOfUser(long userId);

 
    int dataScopeOfUser(long userId);

    
    List<PermInfo> permCatalog();

 
    List<PermGroupInfo> permCatalogGrouped();

    
    List<RolePermOverrideView> permissionsOfRole(long roleId);

    
    List<RolePermOverrideView> savePermissions(long roleId, RolePermissionsInput input);
}
