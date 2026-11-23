package com.sixbean.testpike.core.session.api.dto;

/**
 * Verdict produced by the single decision table over a reported re-check
 * signal (AV5). The status reuses the declaration vocabulary
 * (pending / passed / failed) so the status light and the pre-flight gate
 * keep reading one source.
 *
 * @param status new review status
 * @param detail plain-language wording stored with the verdict (never a secret)
 */
public record LoginExemptReviewVerdict(
        String status,
        String detail) {
}
