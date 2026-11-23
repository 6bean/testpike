package com.sixbean.testpike.core.batch.api.dto;

import com.sixbean.testpike.core.plans.api.dto.SkipItem;

import java.util.List;

/**
 * Batch start result, comprising the contract response fields {batchId, status, queuePos, estimatedStartAt}.
 * Also includes the skip list and a human-readable message, with documented extensions.
 *
 * @param batchId the batch ID.
 * @param status the status code after startup, where 2 means queued for execution and 4 means executing after the executor claims it.
 * @param statusKey the contract literal executing, indicating that the batch has been accepted into the execution pipeline and coexists with the status code.
 *                         env_unavailable is reserved for enhanced executor-side preflight checks.
 * @param statusLabel the Chinese status label.
 * @param queuePos the queue position, indicating where the first batch of tasks sits in the global pending dispatch queue and matching the displayed Nth position.
 * @param estimatedStartAt the estimated start time, calculated heuristically from queued task count divided by online idle capacity, with five-minute granularity.
 * @param skipCases the preflight skip list, covering status mismatches, flaky-case isolation, and cases deleted before startup.
 * @param message a human-readable summary covering skipped cases, missing scripts, environment health warnings, and secondary confirmation text.
 */
public record BatchStartResult(long batchId, int status, String statusKey, String statusLabel,
                               Integer queuePos, String estimatedStartAt,
                               List<SkipItem> skipCases, String message) {
}
