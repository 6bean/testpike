package com.sixbean.testpike.admin.auth.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.config.SsoProperties;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * <ul>
 * </ul>
 *
 */
public final class GatewaySsoClient implements SsoClient {

    private final String baseUrl;
    private final SsoProperties.Gateway paths;
    private final RestClient rest;

    public GatewaySsoClient(String baseUrl, SsoProperties.Gateway paths, RestClient rest) {
        this.baseUrl = baseUrl;
        this.paths = paths;
        this.rest = rest;
    }

    @Override
    public String authorizeUrl(String redirectUri) {
        Map<?, ?> body = rest.get().uri(baseUrl + paths.authorizePath())
                .retrieve().body(Map.class);
        Object data = dataOf(body, "获取 SSO 授权地址失败");
        return String.valueOf(data);
    }

    @Override
    public SsoUser loginByCode(String code) {
        Map<?, ?> body = rest.post().uri(baseUrl + paths.loginPath() + "/" + code)
                .retrieve().body(Map.class);
        Map<?, ?> data = (Map<?, ?>) dataOf(body, "SSO 登录失败");
        Object userId = data.get("userId");
        Object userName = data.get("userName");
        Object nickName = data.get("nickName");
        if (userId == null || userName == null) {
            throw new ServiceException("SSO 登录响应缺少用户信息", BizErrorCode.UNAUTHORIZED);
        }
        List<String> roles = rolesOf(data.get("roles"));
        return new SsoUser(Long.parseLong(String.valueOf(userId)), String.valueOf(userName),
                nickName == null ? String.valueOf(userName) : String.valueOf(nickName),
                data.get("deptName") == null ? "" : String.valueOf(data.get("deptName")),
                roles, data.get("email") == null ? "" : String.valueOf(data.get("email")),
                data.get("avatar") == null ? null : String.valueOf(data.get("avatar")));
    }

    @Override
    public void logout(String token) {
        rest.post().uri(baseUrl + paths.logoutPath())
                .header("Authorization", "Bearer " + token)
                .retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Object dataOf(Map<?, ?> body, String failMsg) {
        if (body == null || !"0".equals(String.valueOf(body.get("code")))) {
            Object msg = body == null ? null : body.get("msg");
            throw new ServiceException(failMsg + (msg == null ? "" : "：" + msg),
                    BizErrorCode.UNAUTHORIZED);
        }
        return body.get("data");
    }

    private static List<String> rolesOf(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
