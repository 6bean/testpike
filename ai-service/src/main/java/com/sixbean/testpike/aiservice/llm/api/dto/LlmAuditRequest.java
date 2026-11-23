package com.sixbean.testpike.aiservice.llm.api.dto;

import java.util.Map;


public record LlmAuditRequest(Long operatorId, Long projectId, String objectType,
                              String objectId, String action,
                              Map<String, Object> before, Map<String, Object> after) {
}
