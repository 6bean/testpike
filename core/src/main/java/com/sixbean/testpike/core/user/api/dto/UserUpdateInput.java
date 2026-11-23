package com.sixbean.testpike.core.user.api.dto;


public record UserUpdateInput(String displayName, String email, String phone, int role, int status) {
}
