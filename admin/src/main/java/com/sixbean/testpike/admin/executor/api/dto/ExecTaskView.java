package com.sixbean.testpike.admin.executor.api.dto;

/**
 *
 * @param taskType Task kind for the list badge (1 case / 2 tool dry-run /
 * 3 explore act / 4 api-test run / 5 pressure / 6 app auto;
 * core-owned vocabulary, admin forwards it untouched)
 * @param perfRunId Pinned pressure run of a task_type=5 row (deep-link target
 * of the badge; null on every other kind or a dirty envelope)
 */
public record ExecTaskView(long id, long projectId, long caseId, Long executorId, int status,
                           String schemaVersion, Integer errorType, String errorDetail,
                           Integer aiFailureClass, String aiFailureNote,
                           String stepsTimeline, String screenshots, Integer durationMs,
                           String log, String resultExt,
                           String startedAt, String finishedAt, String createdAt,
                           int taskType, Long perfRunId) {
}
