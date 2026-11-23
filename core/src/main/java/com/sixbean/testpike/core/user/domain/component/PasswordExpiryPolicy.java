package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;


@Component
public class PasswordExpiryPolicy {

 
    public static final String KEY_EXPIRE_DAYS = "password.expire-days";

    private final ConfigService configService;

    public PasswordExpiryPolicy(ConfigService configService) {
        this.configService = configService;
    }

    
    public long expireDays() {
        try {
            long days = Long.parseLong(configService.getValue(KEY_EXPIRE_DAYS, "0").trim());
            return Math.max(days, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

 
    public boolean isExpired(LocalDateTime passwordChangedAt) {
        long days = expireDays();
        if (days <= 0 || passwordChangedAt == null) {
            return false;
        }
        return passwordChangedAt.plusDays(days).isBefore(LocalDateTime.now());
    }
}
