package com.sixbean.testpike.admin.user.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.api.dto.LocalLoginRequest;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.user.api.dto.UserCreateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserUpdateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.dto.ChangePasswordBody;
import com.sixbean.testpike.admin.user.infrastructure.remote.dto.ProfileUpdateBody;
import com.sixbean.testpike.admin.user.infrastructure.remote.dto.UserResetPasswordBody;
import com.sixbean.testpike.common.client.RestClientInvoker;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;


@Component
public class RestCoreUserClient implements CoreUserClient {

    private final RestClient rest;

    public RestCoreUserClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public UserView authenticate(String username, String password) {
        return call(HttpMethod.POST, "/internal/users/authenticate",
                new LocalLoginRequest(username, password),
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public PageView<UserView> listUsers(String keyword, Integer role, Integer status, int page, int size,
                                        String sortBy, String sortDir) {
        String uri = UriComponentsBuilder.fromPath("/internal/users")
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .queryParamIfPresent("role", Optional.ofNullable(role))
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    
    @Override
    public UserView getUser(long userId) {
        return call(HttpMethod.GET, "/internal/users/" + userId, null, new ParameterizedTypeReference<>() {
        }, null);
    }

    @Override
    public long createUser(UserCreateRequest request) {
        Long id = call(HttpMethod.POST, "/internal/users", request, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
        return id == null ? 0L : id;
    }

    @Override
    public UserView updateUser(long userId, UserUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/users/" + userId, request, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    @Override
    public UserView deleteUser(long userId) {
        return call(HttpMethod.DELETE, "/internal/users/" + userId, null, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    @Override
    public List<RoleView> roles() {
        return call(HttpMethod.GET, "/internal/users/roles", null, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    @Override
    public ImportResultView importUsers(byte[] file, String fileName) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        return callMultipart("/internal/users/import", body);
    }

    @Override
    public ImportResultView retryFailedUsers(byte[] file, String fileName, List<Integer> retryRows) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        retryRows.forEach(row -> body.part("retryRows", row));
        return callMultipart("/internal/users/import/retry", body);
    }

    @Override
    public byte[] userTemplate() {
        return call(HttpMethod.GET, "/internal/users/import/template", null, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    @Override
    public UserView resetPassword(long userId, String newPassword) {
        return call(HttpMethod.POST, "/internal/users/" + userId + "/reset-password",
                new UserResetPasswordBody(newPassword), new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public UserView updateProfile(long userId, String displayName, String avatar) {
        return call(HttpMethod.PUT, "/internal/users/" + userId + "/profile",
                new ProfileUpdateBody(displayName, avatar), new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public UserView changePassword(long userId, String oldPassword, String newPassword) {
        return call(HttpMethod.PUT, "/internal/users/" + userId + "/password",
                new ChangePasswordBody(oldPassword, newPassword), new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    @Override
    public UserView unlock(long userId) {
        return call(HttpMethod.POST, "/internal/users/" + userId + "/unlock", null,
                new ParameterizedTypeReference<>() {
                }, UserContext.currentUserId());
    }

    
    @Override
    public com.sixbean.testpike.admin.user.api.dto.PasswordStatusView passwordStatus(long userId) {
        return call(HttpMethod.GET, "/internal/users/" + userId + "/password-status", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    private ImportResultView callMultipart(String uri, MultipartBodyBuilder body) {
        return RestClientInvoker.callMultipart(rest, uri, body, new ParameterizedTypeReference<>() {
        }, UserContext.currentUserId());
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type,
                       Long operatorId) {
        return RestClientInvoker.call(rest, method, uri, body, type, operatorId);
    }
}
