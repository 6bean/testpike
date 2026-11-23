package com.sixbean.testpike.admin.user.api.dto;


public record UserUpdateRequest(String displayName, String email, String phone, int role, int status) {
}
