package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Input for dismissing a release candidate.
 * <p>The reason is mandatory (validated in ReleaseCandidateService — the single
 * validation point); it is kept in the candidate payload trail for auditing.
 *
 * @param reason plain-word dismissal reason (required)
 */
public record ReleaseCandidateDismissInput(String reason) {
}
