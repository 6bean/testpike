package com.sixbean.testpike.admin.credential.api.dto;


public record CredentialSaveRequest(
        Long projectId,
        String name,
        String credKey,
        String secret,
        String remark) {
}
