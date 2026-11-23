package com.sixbean.testpike.core.cluster.api.dto;

/**
 * Input for recording a detected release candidate (awareness surface: fill only
 * what the feed/import actually provides; missing fields are completed later in
 * the confirmation form — fabricating data is forbidden).
 *
 * @param version        detected release version (required)
 * @param channel        release channel released/prerelease (null = released)
 * @param detectedSource origin poll/webhook/import (null = poll)
 * @param payloadJson    raw detection frame as a JSON object string (release_name /
 *                       release_notes_md / assets ...; null = empty object; must be
 *                       a JSON object)
 */
public record ReleaseCandidateDetectedInput(
        String version,
        String channel,
        String detectedSource,
        String payloadJson) {
}
