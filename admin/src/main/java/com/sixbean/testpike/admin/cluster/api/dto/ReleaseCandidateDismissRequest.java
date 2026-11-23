package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Candidate dismissal request (admin bridge; mirrors the core
 * ReleaseCandidateDismissInput record field-for-field).
 * <p>Blank-reason rejection is a single point in core (human-worded error),
 * the bridge passes it through without a second check (red line 6).
 *
 * @param reason plain-word dismissal reason
 */
public record ReleaseCandidateDismissRequest(String reason) {
}
