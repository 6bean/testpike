package com.sixbean.testpike.admin.session.api.dto;

/**
 * One candidate row shown in the wizard commit checklist . Field
 * names mirror the core CaptureCandidateMetadata record; there is
 * deliberately no value slot here — a captured secret has no screen path,
 * only the executor-to-core commit door (red line 3).
 *
 * @param expiresAtEpochSecond reported expiry, null when the source has none
 */
public record CaptureCandidateView(
        String kind,
        String name,
        String origin,
        Long expiresAtEpochSecond) {
}
