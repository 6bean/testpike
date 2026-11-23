package com.sixbean.testpike.admin.user.api.dto;


public record UserCreateRequest(String username, String password, String displayName,
                                String email, String phone, int role) {
}
