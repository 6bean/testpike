package com.sixbean.testpike.core.cases.api.dto;

/**
 * The use-case update request wraps PUT /internal/cases/{id} with field-level input and an optimistic-lock version.
 *
 * @param input is the field-level update input, where null fields are not updated.
 * @param version is the optimistic-lock version number, which must equal the current version; otherwise, 40901.
 */
public record CaseUpdateRequest(CaseUpdateInput input, int version) {
}
