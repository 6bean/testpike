package com.sixbean.testpike.core.executor.api.dto;

/**
 *
 * @param perfRunId Pinned perf run of a pressure task (task_type=5, read from the
 * dispatch envelope via the single-source reader; every other
 * task type and a dirty envelope = null — trailing addition,
 * zero drift for existing consumers)
 */
public record ExecTaskInfo(long id, long projectId, long caseId, Long executorId, int status,
                           String claimToken, String schemaVersion, String actionJson,
                           String envBaseUrl, String envVersionSnapshot, Integer errorType, String errorDetail,
                           String stepsTimeline, String screenshots, String videoKey,
                           Integer durationMs, String log, String resultExt,
                           Integer aiFailureClass, String aiFailureNote,
                           String startedAt, String finishedAt, String createdAt,
                           int taskType, Long perfRunId) {
}
