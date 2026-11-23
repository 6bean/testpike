package com.sixbean.testpike.admin.user.infrastructure.remote.dto;


public record ChangePasswordBody(String oldPassword, String newPassword) {
}
