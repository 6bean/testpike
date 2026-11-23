package com.sixbean.testpike.admin.audit.api.dto;

import java.time.LocalDateTime;


public record AuditLogView(
        long id,
        Long operatorId,
        Long projectId,
        String objectType,
        String objectId,
        String action,
        String beforeJson,
        String afterJson,
        LocalDateTime createdAt) {
}
