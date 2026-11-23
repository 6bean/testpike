package com.sixbean.testpike.core.audit.api.dto;

import java.util.Map;


public record AuditLogInput(
        Long operatorId,
        Long projectId,
        String objectType,
        String objectId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
}
