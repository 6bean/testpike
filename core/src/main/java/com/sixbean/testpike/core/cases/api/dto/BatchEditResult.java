package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Test case batch edit result (N succeeded / M failed with per-item reasons).
 *
 * @param succeeded list of successfully edited test case IDs
 * @param failures failure details (not found / cross-project, etc., human-readable reasons)
 */
public record BatchEditResult(List<Long> succeeded, List<BatchEditFailureItem> failures) {
}
