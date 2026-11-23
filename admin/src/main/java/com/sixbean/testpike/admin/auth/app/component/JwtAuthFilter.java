package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.config.AuthProperties;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;


@Component
public class JwtAuthFilter extends OncePerRequestFilter {

 
    static final String[] EXPIRY_WHITELIST = {
            "/api/v1/users/me",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
    };

    private final JwtTokenService jwtTokenService;
    private final SessionTokenService sessionTokens;
    private final AuthProperties authProperties;
    private final PasswordPolicyService passwordPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthFilter(JwtTokenService jwtTokenService, SessionTokenService sessionTokens,
                         AuthProperties authProperties, PasswordPolicyService passwordPolicy) {
        this.jwtTokenService = jwtTokenService;
        this.sessionTokens = sessionTokens;
        this.authProperties = authProperties;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/actuator/")
                || path.startsWith("/storage/files/")
                || path.equals("/error")
                || path.contains("favicon");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7).trim();
        }
        try {
            if (token == null || token.isBlank()) {
                throw new com.sixbean.testpike.common.exception.ServiceException(
                        "登录状态已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
            }
            CurrentUser user = jwtTokenService.parse(token);
            if (!authProperties.isSso() && sessionTokens.isJtiRevoked(user.jti())) {
                throw new com.sixbean.testpike.common.exception.ServiceException(
                        "会话已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
            }
            if (!authProperties.isSso() && user.pwdIat() != null
                    && !isExpiryWhitelisted(request.getRequestURI())
                    && passwordPolicy.isExpired(user.userId())) {
                writePasswordExpired(response);
                return;
            }
            UserContext.set(user);
            chain.doFilter(request, response);
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            writeUnauthorized(response, e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

 
    static boolean isExpiryWhitelisted(String path) {
        for (String prefix : EXPIRY_WHITELIST) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    
    private void writePasswordExpired(HttpServletResponse response) throws IOException {
        response.setStatus(428); // Precondition Required
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Response.builderFail(
                String.valueOf(BizErrorCode.PASSWORD_EXPIRED), "密码已过期，请先修改密码")));
    }

    
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Response.builderFail(String.valueOf(BizErrorCode.UNAUTHORIZED), message)));
    }
}
