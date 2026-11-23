package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Batch review result (02-Interface Design 3.2 response data: succeeded + skipped).
 *
 * @param succeeded list of test case IDs that passed review
 * @param skipped details of skipped test cases (terminal state, status changed, nonexistent, or cross-project)
 */
public record ReviewBatchResult(List<Long> succeeded, List<SkippedReviewItem> skipped) {
}
