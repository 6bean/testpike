package com.sixbean.testpike.core.cases.api.dto;

/**
 * Restore request (PUT /internal/cases/{id}/restore wrapper; contract version-based optimistic lock).
 *
 * @param version current version number carried by the recycle-bin row (return 40901 with a human-readable message on mismatch).
 */
public record RestoreCaseRequest(int version) {
}
