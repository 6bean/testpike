package com.sixbean.testpike.core.user.api.dto;


public record PasswordChangeInput(String oldPassword, String newPassword) {
}
