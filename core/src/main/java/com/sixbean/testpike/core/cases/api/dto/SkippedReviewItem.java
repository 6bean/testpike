package com.sixbean.testpike.core.cases.api.dto;

/**
 * Batch review skipped items (02-Interface Design 3.2 response skipped element).
 *
 * @param id ID of the skipped case
 * @param reason Skip reason (human-readable Chinese: terminal-state interception/status changed/case does not exist/does not belong to this project)
 */
public record SkippedReviewItem(long id, String reason) {
}
