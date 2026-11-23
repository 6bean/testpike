package com.sixbean.testpike.core.user.api.dto;


public record UserCreateInput(String username, String password, String displayName,
                              String email, String phone, int role) {
}
