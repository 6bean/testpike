package com.sixbean.testpike.core.cases.api.dto;

/**
 * Batch edit failure details (result report: N successes/M failures with per-item reasons).
 *
 * @param caseId The ID of the failed test case.
 * @param reason A plain-language failure reason (does not exist, cross-project isolation, etc.).
 */
public record BatchEditFailureItem(long caseId, String reason) {
}
