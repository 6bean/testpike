package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Test case auto-assignment request (contract POST /cases/auto-assign).
 *
 * @param projectId The project ID, which is the isolation dimension; return 404 if it does not exist.
 * @param caseIds The specified test case ID list, where null means all unassigned test cases pending review in the project, matching the prompt line exactly.
 *                  When explicitly provided, cross-project or nonexistent test cases return 400 with a human-readable message.
 * @param preview true for preview mode (return the dispatch plan with no writes and no notifications); false for formal dispatch.
 */
public record AutoAssignRequest(long projectId, List<Long> caseIds, boolean preview) {
}
