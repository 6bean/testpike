package com.sixbean.testpike.core.user.api.dto;


public record PasswordStatus(long userId, boolean passwordExpired, long expireDays) {
}
