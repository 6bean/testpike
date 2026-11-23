package com.sixbean.testpike.core.cases.api.dto;

/**
 * Request body for cloning a case via POST /cases/{id}/clone with an optional targetProjectId.
 * If null, clone into the source case's project; if non-null, clone across projects, and the core validates that the target project exists (plain-language 404).
 * The operator must be a member of the target project (plain-language 403).
 *
 * @param targetProjectId the target project ID (null means same project).
 */
public record CloneCaseRequest(Long targetProjectId) {
}
