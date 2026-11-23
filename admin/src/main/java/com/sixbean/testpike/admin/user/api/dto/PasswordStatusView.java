package com.sixbean.testpike.admin.user.api.dto;


public record PasswordStatusView(long userId, boolean passwordExpired, long expireDays) {
}
