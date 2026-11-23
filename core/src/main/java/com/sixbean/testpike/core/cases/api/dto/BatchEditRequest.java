package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Input parameters for batch editing test cases (02-Interface Design 2.4 extension: POST /cases/batch-edit).
 *
 * <p>Semantics: at least one editable field must be non-null (phase/priority/tagIds may be null to skip update); when tagIds is non-null.
 * Overwrite-style setting (empty list clears tags, same semantics as PUT /cases/{id}/tags); per-item validation is non-atomic.
 * The result report contains N successes and M failures; tags must belong to the same project as the test case (project isolation).
 *
 * @param projectId Project ID (isolation dimension).
 * @param caseIds Target test case ID list (non-empty, ≤100, deduplicated).
 * @param phase Test case phase: 1=smoke, 2=functional, 3=regression, 4=release verification (null means no update).
 * @param priority Priority (null means no update).
 * @param tagIds Tag ID list (null means no update; empty list clears tags).
 */
public record BatchEditRequest(Long projectId, List<Long> caseIds,
                               Integer phase, Integer priority, List<Long> tagIds) {
}
