package com.sixbean.testpike.admin.executor.api.dto;


public record RunningTaskView(long taskId, long projectId, String caseTitle, Long batchId,
                              Long executorId, String startedAt, long elapsedMs, long timeoutMs) {
}
