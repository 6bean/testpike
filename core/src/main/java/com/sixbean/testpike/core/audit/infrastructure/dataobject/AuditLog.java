package com.sixbean.testpike.core.audit.infrastructure.dataobject;

import java.time.LocalDateTime;


public record AuditLog(
        Long id,
        Long operatorId,
        Long projectId,
        String objectType,
        String objectId,
        String action,
        String beforeJson,
        String afterJson,
        String prevHash,
        String curHash,
        LocalDateTime createdAt) {
}
