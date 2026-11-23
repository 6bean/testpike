package com.sixbean.testpike.core.cases.api.dto;

/**
 * Request body for the POST version-compare door .
 * The HTTP tool executor always dials exec_kind=1 endpoints with the model's
 * raw input JSON as the body, so the compare read face needs a body-shaped
 * entry beside the GET field-level diff door it delegates to.
 *
 * @param caseId the case whose history is compared (mandatory anchor)
 * @param fromVersion baseline version (optional: absent = compare against the
 * case's latest snapshot version, same default as GET door)
 * @param toVersion target version shown on the card (mandatory, >= 1)
 */
public record CaseVersionCompareRequest(Long caseId, Integer fromVersion, Integer toVersion) {
}
