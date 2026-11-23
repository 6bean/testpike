package com.sixbean.testpike.admin.audit.api.dto;

import java.util.List;
import java.util.Map;


public record AccessDeniedAuditRequest(Long operatorId, Long projectId, String objectType,
                                       String objectId, String action,
                                       Map<String, Object> before, Map<String, Object> after) {

    
    public static AccessDeniedAuditRequest compareDenied(Long operatorId, List<Long> attempted,
                                                         long deniedProjectId) {
        return new AccessDeniedAuditRequest(operatorId, deniedProjectId, "report", "compare",
                "ACCESS_DENIED",
                Map.of("attempted", attempted, "denied", deniedProjectId, "endpoint", "compare"),
                null);
    }
}
