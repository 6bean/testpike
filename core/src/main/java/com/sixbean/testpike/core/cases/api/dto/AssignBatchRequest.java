package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Batch assignment request for POST /cases/batch-assign.
 *
 * @param projectId Project ID, which is the ownership dimension for member validation and load balancing, with H1 isolation.
 * @param caseIds List of case IDs to assign, containing 1 to 100 deduplicated entries, where the whole operation is rolled back if any case does not exist or has an invalid status.
 * @param assigneeId Target member for assignment, where empty means automatic load balancing that prioritizes the member with the fewest unrated cases and keeps the difference at most 1.
 */
public record AssignBatchRequest(Long projectId, List<Long> caseIds, Long assigneeId) {
}
