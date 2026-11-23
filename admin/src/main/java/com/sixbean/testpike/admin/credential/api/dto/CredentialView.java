package com.sixbean.testpike.admin.credential.api.dto;

import java.time.LocalDateTime;


public record CredentialView(
        Long id,
        Long projectId,
        String name,
        String credKey,
        String secretMask,
        Integer status,
        String remark,
        String testGrade,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Legacy shape kept for callers that do not surface the evidence grade. */
    public CredentialView(Long id, Long projectId, String name, String credKey, String secretMask,
                          Integer status, String remark,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, name, credKey, secretMask, status, remark, null, createdAt, updatedAt);
    }
}
