package com.sixbean.testpike.core.user.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.user.api.dto.AuthenticateRequest;
import com.sixbean.testpike.core.user.api.dto.PasswordChangeInput;
import com.sixbean.testpike.core.user.api.dto.ResetPasswordInput;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.api.dto.UserQuery;
import com.sixbean.testpike.core.user.api.dto.UserUpdateInput;
import com.sixbean.testpike.core.user.domain.service.UserService;


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


@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserService userService;

    public UserInternalController(UserService userService) {
        this.userService = userService;
    }

    
    @PostMapping("/authenticate")
    public Response<UserInfo> authenticate(@RequestBody AuthenticateRequest request) {
        return Response.builderSuccess(userService.authenticate(request.username(), request.password()));
    }

    
    @GetMapping("/{id}")
    public Response<UserInfo> get(@PathVariable long id) {
        return Response.builderSuccess(userService.get(id));
    }

 
    @GetMapping("/{id}/password-status")
    public Response<com.sixbean.testpike.core.user.api.dto.PasswordStatus> passwordStatus(@PathVariable long id) {
        return Response.builderSuccess(userService.passwordStatus(id));
    }

    
    @GetMapping
    public Response<com.sixbean.testpike.core.common.clientobject.PageResult<UserInfo>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer role,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                userService.list(new UserQuery(keyword, role, status, page, size, sortBy, sortDir)));
    }

    
    @PostMapping
    public Response<Long> create(@RequestBody UserCreateInput input) {
        return Response.builderSuccess(userService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<UserInfo> update(@PathVariable long id, @RequestBody UserUpdateInput input) {
        return Response.builderSuccess(userService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @PutMapping("/{id}/password")
    public Response<UserInfo> changePassword(@PathVariable long id, @RequestBody PasswordChangeInput input) {
        return Response.builderSuccess(userService.changePassword(id, input.oldPassword(), input.newPassword()));
    }

    
    @DeleteMapping("/{id}")
    public Response<UserInfo> delete(@PathVariable long id) {
        return Response.builderSuccess(userService.delete(OperatorContext.currentOperatorId(), id));
    }

    
    @GetMapping("/roles")
    public Response<List<com.sixbean.testpike.core.rbac.api.dto.RoleInfo>> roles() {
        return Response.builderSuccess(userService.roles());
    }

    
    @PostMapping("/import")
    public Response<ImportResult> importUsers(@RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(userService.importUsers(OperatorContext.currentOperatorId(),
                file.getBytes(), file.getOriginalFilename()));
    }

    
    @PostMapping("/import/retry")
    public Response<ImportResult> retryFailedUsers(@RequestParam List<Integer> retryRows,
                                                   @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(userService.retryFailedUsers(OperatorContext.currentOperatorId(),
                file.getBytes(), file.getOriginalFilename(), retryRows));
    }

    
    @GetMapping("/import/template")
    public Response<byte[]> userTemplate() {
        return Response.builderSuccess(userService.userTemplate());
    }

    
    @PostMapping("/{id}/reset-password")
    public Response<UserInfo> resetPassword(@PathVariable long id, @RequestBody ResetPasswordInput input) {
        return Response.builderSuccess(userService.resetPassword(OperatorContext.currentOperatorId(),
                id, input.newPassword()));
    }

    
    @PutMapping("/{id}/profile")
    public Response<UserInfo> updateProfile(@PathVariable long id,
                                            @RequestBody com.sixbean.testpike.core.user.api.dto.ProfileUpdateInput input) {
        return Response.builderSuccess(userService.updateProfile(id, input.displayName(), input.avatar()));
    }

 
    @PostMapping("/{id}/unlock")
    public Response<UserInfo> unlock(@PathVariable long id) {
        return Response.builderSuccess(userService.unlock(OperatorContext.currentOperatorId(), id));
    }
}
