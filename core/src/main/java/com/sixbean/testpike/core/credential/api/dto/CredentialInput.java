package com.sixbean.testpike.core.credential.api.dto;


public record CredentialInput(
        String name,
        String credKey,
        String secret,
        String remark) {
}
