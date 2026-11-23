package com.sixbean.testpike.admin.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "sso")
public record SsoProperties(String mode, Gateway gateway, Jwt jwt, Mock mock) {

    public record Gateway(String baseUrl, String authorizePath, String loginPath,
                          String userinfoPath, String logoutPath) {
    }

    public record Jwt(String secret, long ttlSeconds) {
    }

    public record Mock(String frontendBase) {
    }
}
