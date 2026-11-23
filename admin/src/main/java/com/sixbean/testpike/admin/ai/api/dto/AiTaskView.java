package com.sixbean.testpike.admin.ai.api.dto;


public record AiTaskView(long id, long projectId, long operatorId, int taskType,
                         int status, String requirementText, Long requirementId,
                         Long caseId, Long scriptId, String errorCode, String errorMsg,
                         int retryCnt, String createdAt, String updatedAt) {
}
