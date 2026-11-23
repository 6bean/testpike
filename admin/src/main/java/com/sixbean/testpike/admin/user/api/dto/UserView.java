package com.sixbean.testpike.admin.user.api.dto;


import java.time.LocalDateTime;


public record UserView(Long id, String username, String displayName, String email, String phone,
                       String avatar, int role, int status, int deleted, Long createdBy,
                       LocalDateTime createdAt, LocalDateTime updatedAt,
                       int failCount, LocalDateTime lockedUntil, String lockReason,
                       LocalDateTime passwordChangedAt) {

 
    public UserView(Long id, String username, String displayName, String email, String phone,
                    String avatar, int role, int status, int deleted, Long createdBy,
                    LocalDateTime createdAt, LocalDateTime updatedAt,
                    int failCount, LocalDateTime lockedUntil, String lockReason) {
        this(id, username, displayName, email, phone, avatar, role, status, deleted, createdBy,
                createdAt, updatedAt, failCount, lockedUntil, lockReason, null);
    }
}
