package com.sixbean.testpike.admin.auth.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.app.component.MockUserCatalog;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.MockSsoClient;


import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MockSsoClientTest {

    private final MockSsoClient client = new MockSsoClient("http://localhost:8081", new MockUserCatalog());

    @Test
    void authorizeUrl_回跳redirectUri并携带mockCode() {
        String url = client.authorizeUrl("http://localhost:5173/sso-callback");
        assertTrue(url.startsWith("http://localhost:5173/sso-callback?code=mock-"),
                "mock 授权 URL 必须回跳调用方 redirect_uri 并携带 code，实际: " + url);
    }

    @Test
    void loginByCode_内置用户可登录() {
        List<SsoUser> users = new MockUserCatalog().all();
        for (SsoUser u : users) {
            SsoUser got = client.loginByCode("mock-" + u.username());
            assertEquals(u.userId(), got.userId());
            assertEquals(u.username(), got.username());
            assertNotNull(got.roles());
        }
    }

    @Test
    void loginByCode_非法code被拒绝() {
        assertThrows(Exception.class, () -> client.loginByCode("mock-ghost-user"), "未知用户 code 必须拒绝");
        assertThrows(Exception.class, () -> client.loginByCode("random"), "非 mock 前缀 code 必须拒绝");
        assertThrows(Exception.class, () -> client.loginByCode(""), "空 code 必须拒绝");
    }

    @Test
    void logout_幂等不抛异常() {
        client.logout("any-token");
        client.logout("");
    }

    @Test
    void mock用户目录包含四角色覆盖() {
        MockUserCatalog catalog = new MockUserCatalog();
        assertEquals(3, catalog.all().size(), "mock 用户目录应含 3 个内置用户（管理员/测试/开发）");
    }
}
