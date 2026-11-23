package com.sixbean.testpike.admin.auth.api.dto;


public record ChangeMyPasswordRequest(String oldPassword, String newPassword) {
}
