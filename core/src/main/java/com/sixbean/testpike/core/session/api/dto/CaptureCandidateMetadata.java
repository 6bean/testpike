package com.sixbean.testpike.core.session.api.dto;

/**
 * round 2): metadata only — the entry <em>value</em> has no field here at all,
 * so a captured secret cannot ride this shape into any store, log or screen
 * (red line 3). The commit round re-reads the chosen keys from the browser
 * session instead of ever persisting them here.
 *
 * @param kind cookie | local_storage
 * @param name entry name the operator ticks in the wizard
 * @param origin origin the entry belongs to (already whitelisted)
 * @param expiresAtEpochSecond reported expiry, null when the source has none
 */
public record CaptureCandidateMetadata(
        String kind,
        String name,
        String origin,
        Long expiresAtEpochSecond) {
}
