package com.sixbean.testpike.core.user.api.dto;


public record UserImportRow(int rowNumber, String username, String displayName, String email,
                            String phone, Integer role, String password, String failReason) {
}
