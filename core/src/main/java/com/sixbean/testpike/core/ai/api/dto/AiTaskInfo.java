package com.sixbean.testpike.core.ai.api.dto;

/**
 * AI-generated task information (GET /ai/tasks/{taskId} query contract).
 *
 */
public record AiTaskInfo(long id, long projectId, long operatorId, int taskType,
                         int status, String requirementText, Long requirementId,
                         Long caseId, Long scriptId, String errorCode, String errorMsg,
                         int retryCnt, String modelName, String createdAt, String updatedAt,
                         Long templateVersionId) {
}
