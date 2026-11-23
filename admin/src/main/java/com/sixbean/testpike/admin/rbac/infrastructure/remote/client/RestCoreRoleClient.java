package com.sixbean.testpike.admin.rbac.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.api.dto.PermGroupView;
import com.sixbean.testpike.admin.rbac.api.dto.PermView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleFuncsRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleSaveRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.rbac.api.dto.UserRolesRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;


@Component
public class RestCoreRoleClient implements CoreRoleClient {

    private final RestClient rest;

    public RestCoreRoleClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<RoleView> roles() {
        return call(HttpMethod.GET, "/internal/roles", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public RoleView getRole(long roleId) {
        return call(HttpMethod.GET, "/internal/roles/" + roleId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public long createRole(RoleSaveRequest request) {
        Long id = call(HttpMethod.POST, "/internal/roles", request, new ParameterizedTypeReference<>() {
        });
        return id == null ? 0L : id;
    }

    @Override
    public RoleView updateRole(long roleId, RoleSaveRequest request) {
        return call(HttpMethod.PUT, "/internal/roles/" + roleId, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void deleteRole(long roleId) {
        call(HttpMethod.DELETE, "/internal/roles/" + roleId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<String> funcsOfRole(long roleId) {
        return call(HttpMethod.GET, "/internal/roles/" + roleId + "/funcs", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void updateFuncs(long roleId, RoleFuncsRequest request) {
        call(HttpMethod.PUT, "/internal/roles/" + roleId + "/funcs", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<RoleView> rolesOfUser(long userId) {
        return call(HttpMethod.GET, "/internal/roles/users/" + userId + "/roles", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void assignUserRoles(long userId, UserRolesRequest request) {
        call(HttpMethod.PUT, "/internal/roles/users/" + userId + "/roles", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<PermView> permCatalog() {
        return call(HttpMethod.GET, "/internal/roles/perms", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<PermGroupView> permCatalogGrouped() {
        return call(HttpMethod.GET, "/internal/roles/perms/catalog", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<RolePermOverrideView> permissionsOfRole(long roleId) {
        return call(HttpMethod.GET, "/internal/roles/" + roleId + "/permissions", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<RolePermOverrideView> savePermissions(long roleId, RolePermOverrideRequest request) {
        return call(HttpMethod.PUT, "/internal/roles/" + roleId + "/permissions", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public Set<String> permsOfUser(long userId) {
        return call(HttpMethod.GET, "/internal/roles/users/" + userId + "/perms", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public int dataScopeOfUser(long userId) {
        Integer scope = call(HttpMethod.GET, "/internal/roles/users/" + userId + "/data-scope", null,
                new ParameterizedTypeReference<>() {
                });
        return scope == null ? 2 : scope;
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
