package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Batch case export request (POST /cases/export body).
 * <p>ids (export selected) and filter (filtered export) are mutually exclusive: if both are empty, return 400 "Please select export scope".
 * If both are provided, return 400 "Export selected and filtered export are mutually exclusive"; filter is a subset of the list-page filter conditions.
 * projectId is required (isolation dimension, with both admin-side authentication and core data scope enforced).
 *
 * @param projectId Project ID (required).
 * @param ids Selected case ID list (export-selected mode; cases that are not visible or have been deleted are silently removed, with total/removed hints).
 * @param status Filtered export: status 1 through 9 (null = all).
 * @param keyword Filtered export: keyword (matches title, case number, precondition, expected result, and steps, using the same criteria as the list page).
 * @param priority Filtered export: priority (null = all).
 * @param confidence Filtered export: confidence (null = all).
 * @param assigneeId Filtered export: assignee (null = all).
 * @param phase Filtered export: case phase (null = all).
 * @param tagIds Filtered export: tag ID list (null = all).
 */
public record CaseExportRequest(long projectId, List<Long> ids, Integer status, String keyword,
                                Integer priority, Integer confidence, Long assigneeId,
                                Integer phase, List<Long> tagIds) {
}
