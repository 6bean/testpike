package com.sixbean.testpike.core.cases.api.dto;

/**
 * Flaky isolation/un-isolation request (contract PUT /cases/{id}/flaky body {isolated}).
 *
 * @param isolated true=one-click isolation (batch preflight check skipped) / false=un-isolate and resume participation
 * @param reason isolation reason (nullable; when empty, the server fills a default human-readable message)
 */
public record CaseFlakyRequest(Boolean isolated, String reason) {
}
