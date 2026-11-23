package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JwtAuthFilterPasswordExpiryTest {

    private JwtTokenService jwtTokenService;
    private SessionTokenService sessionTokens;
    private PasswordPolicyService passwordPolicy;
    private com.sixbean.testpike.admin.auth.config.AuthProperties authProperties;
    private JwtAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtTokenService = mock(JwtTokenService.class);
        sessionTokens = mock(SessionTokenService.class);
        passwordPolicy = mock(PasswordPolicyService.class);
        authProperties = new com.sixbean.testpike.admin.auth.config.AuthProperties("local");
        filter = new JwtAuthFilter(jwtTokenService, sessionTokens, authProperties, passwordPolicy);
        chain = mock(FilterChain.class);
        when(jwtTokenService.jtiOf(anyString())).thenReturn(null);
        when(sessionTokens.isJtiRevoked(any())).thenReturn(false);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private void stubToken(long userId, Long pwdIat) {
        CurrentUser cu = new CurrentUser(userId, "u", "U", "D", List.of(),
                System.currentTimeMillis() / 1000 + 3600, null, "jti-1", pwdIat);
        when(jwtTokenService.parse("t-" + userId)).thenReturn(cu);
    }

    private MockHttpServletRequest request(String path, long userId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.addHeader("Authorization", "Bearer t-" + userId);
        return req;
    }

    @Test
    void 过期用户访问业务接口_428人话且不进链() throws Exception {
        stubToken(7L, 1L);
        when(passwordPolicy.isExpired(7L)).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/cases", 7L), resp, chain);

        assertEquals(428, resp.getStatus(), "过期+非白名单 → 428 Precondition Required");
        String body = resp.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("密码已过期"), "人话: " + body);
        assertTrue(body.contains("42801"), "业务码 42801: " + body);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 白名单端点_过期用户放行改密闭环() throws Exception {
        stubToken(8L, 1L);
        when(passwordPolicy.isExpired(8L)).thenReturn(true);

        for (String path : List.of("/api/v1/users/me", "/api/v1/users/me/password",
                "/api/v1/auth/logout", "/api/v1/auth/refresh")) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(request(path, 8L), resp, chain);
            assertEquals(200, resp.getStatus(), "白名单放行: " + path);
        }
        verify(chain, org.mockito.Mockito.times(4)).doFilter(any(), any());
    }

    @Test
    void 无pwdIat旧token不检查不误伤() throws Exception {
        stubToken(9L, null);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/cases", 9L), resp, chain);

        assertEquals(200, resp.getStatus());
        verify(passwordPolicy, never()).isExpired(9L);
    }

    @Test
    void 过期用户改密后即时放行_判定实时无缓存() throws Exception {
        stubToken(10L, 1L);
        when(passwordPolicy.isExpired(10L)).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/cases", 10L), resp, chain);

        assertEquals(200, resp.getStatus());
        verify(passwordPolicy).isExpired(10L);
    }

    @Test
    void SSO模式跳过R5检查() throws Exception {
        filter = new JwtAuthFilter(jwtTokenService, sessionTokens,
                new com.sixbean.testpike.admin.auth.config.AuthProperties("sso"), passwordPolicy);
        stubToken(11L, 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(request("/api/v1/cases", 11L), resp, chain);

        assertEquals(200, resp.getStatus());
        verify(passwordPolicy, never()).isExpired(11L);
    }
}
