package com.sixbean.testpike.admin.ai.api.dto;


public record AiBatchItemView(long taskId, int status, String requirementText,
                              Long caseId, String errorCode, String errorMsg) {
}
