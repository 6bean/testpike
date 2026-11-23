package com.sixbean.testpike.aiservice.explore.infrastructure.remote;


public record ExecTaskResultRemote(long id, long projectId, long caseId, Long executorId, int status,
                                   String claimToken, String schemaVersion, String actionJson,
                                   String envBaseUrl, String envVersionSnapshot,
                                   Integer errorType, String errorDetail,
                                   String stepsTimeline, String screenshots, String videoKey,
                                   Integer durationMs, String log, String startedAt,
                                   String finishedAt, String createdAt) {
}
