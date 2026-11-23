package com.sixbean.testpike.core.cases.api.dto;

/**
 * Review reminder scan result (internal scheduled task in core; registered as a server-side scheduled task with no new external interface).
 * <p>Two-level semantics: remind reviewers when remind-hours is exceeded (REVIEW_REMIND); escalate to the project owner when timeout-hours is exceeded.
 * The REVIEW_TIMEOUT level is mandatory subscription, and the idempotent deduplication key is (use case, recipient, calendar day) using tp_notification.dedup_key.
 * The documentation records zero new tables and reminder idempotency.
 *
 * @param scanned The number of to-do items scanned (status 1 or 2, assigned, and with an assignment time).
 * @param reminded The number of reminder notifications actually sent in this round after deduplication (REVIEW_REMIND).
 * @param escalated The number of escalation notifications actually sent in this round after deduplication (REVIEW_TIMEOUT).
 * @param deduped The number of notifications skipped due to same-day deduplication (repeated scans do not resend).
 */
public record ReviewRemindResult(int scanned, int reminded, int escalated, int deduped) {
}
