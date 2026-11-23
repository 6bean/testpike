package com.sixbean.testpike.admin.ai.api.dto;


public record AiGenerateRequest(long projectId, String requirement, Long requirementId) {
}
