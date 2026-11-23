package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Release candidate view (admin bridge; field-for-field mirror of the core
 * ReleaseCandidateInfo record — no reprocessing, no second judgement).
 * <p>payload stays a raw JSON string passed through verbatim (the frontend
 * parses it at the single consumption point, same posture as ReleaseManifestView).
 *
 * @param id                  candidate row id
 * @param version             detected release version
 * @param channel             released/prerelease
 * @param detectedSource      poll/webhook/import
 * @param detectedAt          latest detection time
 * @param payloadJson         raw detection frame json string (name mirrors the
 *                            core record field — same-name direct mapping)
 * @param status              detected/confirmed/dismissed/replaced
 * @param confirmedManifestId authoritative manifest row id (null until confirmed)
 */
public record ReleaseCandidateView(
        Long id,
        String version,
        String channel,
        String detectedSource,
        LocalDateTime detectedAt,
        String payloadJson,
        String status,
        Long confirmedManifestId) {
}
