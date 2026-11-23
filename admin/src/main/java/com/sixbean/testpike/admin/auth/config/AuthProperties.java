package com.sixbean.testpike.admin.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "auth")
public record AuthProperties(String mode) {

    
    public boolean isLocal() {
        return !"sso".equalsIgnoreCase(mode);
    }

    
    public boolean isSso() {
        return "sso".equalsIgnoreCase(mode);
    }
}
