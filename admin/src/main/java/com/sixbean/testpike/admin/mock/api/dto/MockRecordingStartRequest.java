package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Recording start request body (admin bridge intake for .
 * Only the upstream target travels in the body; the space rides the path.
 *
 * @param targetBaseUrl upstream base URL to record (allowlist-gated by core)
 */
public record MockRecordingStartRequest(String targetBaseUrl) {
}
