package com.sixbean.testpike.core.user.api.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;


public record UserInfo(Long id, String username, String displayName, String email, String phone,
                       String avatar, int role, int status, int deleted, Long createdBy,
                       LocalDateTime createdAt, LocalDateTime updatedAt,
                       int failCount, LocalDateTime lockedUntil, String lockReason,
                       @JsonIgnore @JSONField(serialize = false) String passwordHash,
                       LocalDateTime passwordChangedAt) {

    
    public boolean isAdmin() {
        return role == 4;
    }
}
