package com.sixbean.testpike.core.ai.api.dto;

/**
 * AI batch generates subtask entries (batch progress x/N details: individual task status + result/failure reason).
 *
 */
public record AiBatchItemInfo(long taskId, int status, String requirementText,
                              Long caseId, String errorCode, String errorMsg) {
}
