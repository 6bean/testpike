package com.sixbean.testpike.admin.auth.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.ChangeMyPasswordRequest;
import com.sixbean.testpike.admin.auth.api.dto.LocalLoginRequest;
import com.sixbean.testpike.admin.auth.api.dto.LoginRequest;
import com.sixbean.testpike.admin.auth.api.dto.LoginResult;
import com.sixbean.testpike.admin.auth.api.dto.LogoutRequest;
import com.sixbean.testpike.admin.auth.api.dto.MeResult;
import com.sixbean.testpike.admin.auth.api.dto.RefreshRequest;
import com.sixbean.testpike.admin.auth.api.dto.UpdateProfileRequest;
import com.sixbean.testpike.admin.auth.app.service.AuthService;


import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <ul>
 * </ul>
 *
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

 
    @PostMapping("/auth/login")
    public Response<LoginResult> localLogin(@RequestBody LocalLoginRequest request) {
        return Response.builderSuccess(authService.localLogin(request.username(), request.password()));
    }

    
    @GetMapping("/auth/sso/authorize-url")
    public Response<String> authorizeUrl(@RequestParam(value = "redirect_uri", required = false) String redirectUri) {
        return Response.builderSuccess(authService.authorizeUrl(redirectUri));
    }

    
    @PostMapping("/auth/sso/login")
    public Response<LoginResult> login(@RequestBody LoginRequest request) {
        return Response.builderSuccess(authService.login(request.code()));
    }

    
    @PostMapping("/auth/logout")
    public Response<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody(required = false) LogoutRequest request) {
        authService.logout(bearerToken(authorization),
                request == null ? null : request.refreshToken());
        return Response.builderSuccess();
    }

    
    @PostMapping("/auth/refresh")
    public Response<LoginResult> refresh(@RequestBody RefreshRequest request) {
        return Response.builderSuccess(authService.refresh(request.refreshToken()));
    }

    
    @GetMapping("/users/me")
    public Response<MeResult> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return Response.builderSuccess(authService.me(bearerToken(authorization)));
    }

    
    @PutMapping("/users/me")
    public Response<LoginResult> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody UpdateProfileRequest request) {
        return Response.builderSuccess(
                authService.updateProfile(bearerToken(authorization), request.displayName(), request.avatar()));
    }

    
    @PutMapping("/users/me/password")
    public Response<Void> changeMyPassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ChangeMyPasswordRequest request) {
        authService.changePassword(bearerToken(authorization), request.oldPassword(), request.newPassword());
        return Response.builderSuccess();
    }

    
    private String bearerToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return authorization;
    }
}
