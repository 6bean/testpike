package com.sixbean.testpike.admin.judge.api.dto;

/**
 * One historical question-generation run bridged from ai-service (revisit
 * door of the tail window). Same field shape as the ai-service view; the
 * timestamps ride as ISO strings so the history list renders them without a
 * second decoder (pattern of {@link QaGenEventView}).
 *
 * @param runToken   run identity to replay through the event window door
 * @param status     running / done / failed
 * @param eventCount process frames landed for this run
 * @param firstAt    ISO timestamp of the run's first frame
 * @param lastAt     ISO timestamp of the run's newest frame
 */
public record QaGenRunView(String runToken, String status, long eventCount,
                           String firstAt, String lastAt) {
}
