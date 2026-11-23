package com.sixbean.testpike.aiservice.judge.api.dto;

import java.time.LocalDateTime;

/**
 * One historical question-generation run of a document (revisit door of the
 * tail window). Aggregated read-side from the process event ledger — no new
 * write path: {@code status} resolves to running when the token matches the
 * live job row, failed when the stream carries an error frame, done otherwise.
 *
 * @param runToken   run identity shared with the event window door
 * @param status     running / done / failed (job plane closed set)
 * @param eventCount process frames landed for this run
 * @param firstAt    timestamp of the run's first frame
 * @param lastAt     timestamp of the run's newest frame
 */
public record QaGenRunView(String runToken, String status, long eventCount,
                           LocalDateTime firstAt, LocalDateTime lastAt) {
}
