package com.sixbean.testpike.core.batch.domain.component;

/**
 * Per-case decision row for the batch-start preflight check (shared input for common components; used by both plan-generated batches and user-side startup).
 *
 * @param caseId Case ID
 * @param caseNo Case number
 * @param title Case title
 * @param status Current case status (3=passed review)
 * @param flaky Flaky isolation flag (1=isolated, prioritize skipping)
 */
public record BatchPrecheckRow(long caseId, String caseNo, String title, int status, int flaky) {
}
