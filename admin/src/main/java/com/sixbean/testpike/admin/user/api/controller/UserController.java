package com.sixbean.testpike.admin.user.api.controller;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.admin.auth.app.component.SessionTokenService;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.admin.user.api.dto.ResetPasswordRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.user.api.dto.UserCreateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserUpdateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Set;


@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final CoreUserClient coreUserClient;
    private final CoreRoleClient coreRoleClient;
    private final SessionTokenService sessionTokens;
    private final com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService passwordPolicy;

    public UserController(CoreUserClient coreUserClient, CoreRoleClient coreRoleClient,
                          SessionTokenService sessionTokens,
                          com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService passwordPolicy) {
        this.coreUserClient = coreUserClient;
        this.coreRoleClient = coreRoleClient;
        this.sessionTokens = sessionTokens;
        this.passwordPolicy = passwordPolicy;
    }

    
    @GetMapping
    @RequirePermission("perm:system:user-manage")
    public Response<PageView<UserView>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer role,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String sortBy,
                                             @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreUserClient.listUsers(keyword, role, status, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:system:user-manage")
    public Response<UserView> get(@PathVariable long id) {
        return Response.builderSuccess(coreUserClient.getUser(id));
    }

    
    @PostMapping
    @RequirePermission("perm:system:user-manage")
    public Response<Long> create(@RequestBody UserCreateRequest request) {
        return Response.builderSuccess(coreUserClient.createUser(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:system:user-manage")
    public Response<UserView> update(@PathVariable long id, @RequestBody UserUpdateRequest request) {
        return Response.builderSuccess(coreUserClient.updateUser(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:system:user-manage")
    public Response<UserView> delete(@PathVariable long id) {
        return Response.builderSuccess(coreUserClient.deleteUser(id));
    }

    
    @GetMapping("/roles")
    @RequirePermission("perm:system:user-manage")
    public Response<List<RoleView>> roles() {
        return Response.builderSuccess(coreUserClient.roles());
    }

 
    @GetMapping("/{id}/roles")
    @RequirePermission("perm:system:role-manage")
    public Response<List<com.sixbean.testpike.admin.rbac.api.dto.RoleView>> rolesOfUser(@PathVariable long id) {
        return Response.builderSuccess(coreRoleClient.rolesOfUser(id));
    }

    
    @PutMapping("/{id}/roles")
    @RequirePermission("perm:system:role-manage")
    public Response<Void> assignUserRoles(@PathVariable long id,
                                          @RequestBody com.sixbean.testpike.admin.rbac.api.dto.UserRolesRequest request) {
        coreRoleClient.assignUserRoles(id, request);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/me/perms")
    public Response<Set<String>> myPerms() {
        return Response.builderSuccess(coreRoleClient.permsOfUser(UserContext.currentUserId()));
    }

    
    @PostMapping("/import")
    @RequirePermission("perm:system:user-manage")
    public Response<ImportResultView> importUsers(@RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(coreUserClient.importUsers(file.getBytes(), file.getOriginalFilename()));
    }

    
    @PostMapping("/import/retry")
    @RequirePermission("perm:system:user-manage")
    public Response<ImportResultView> retryFailedUsers(@RequestParam List<Integer> retryRows,
                                                       @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(coreUserClient.retryFailedUsers(file.getBytes(), file.getOriginalFilename(), retryRows));
    }

    
    @GetMapping("/import/template")
    @RequirePermission("perm:system:user-manage")
    public Response<byte[]> importTemplate() {
        return Response.builderSuccess(coreUserClient.userTemplate());
    }

 
    @PostMapping("/{id}/unlock")
    @RequirePermission("perm:system:user-manage")
    public Response<UserView> unlock(@PathVariable long id) {
        return Response.builderSuccess(coreUserClient.unlock(id));
    }

    
    @PostMapping("/{id}/reset-password")
    @RequirePermission("perm:system:user-manage")
    public Response<UserView> resetPassword(@PathVariable long id, @RequestBody ResetPasswordRequest request) {
        if (id == UserContext.currentUserId()) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "不能重置当前登录账号的密码，请通过个人中心修改",
                    com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
        UserView view = coreUserClient.resetPassword(id, request.newPassword());
        sessionTokens.revokeAllSessions(id);
        passwordPolicy.evict(id);
        return Response.builderSuccess(view);
    }
}
