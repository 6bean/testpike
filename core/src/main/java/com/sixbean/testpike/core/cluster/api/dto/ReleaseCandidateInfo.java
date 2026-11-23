package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Release candidate view (awareness surface; the only promotion path is
 * ReleaseManifestService.register via confirm).
 *
 * @param id                  candidate row id
 * @param version             detected release version (unique row)
 * @param channel             release channel released/prerelease
 * @param detectedSource      origin poll/webhook/import
 * @param detectedAt          latest detection time (refreshed on re-detection)
 * @param payloadJson         raw detection frame passed through verbatim (dismissal
 *                            reason is also kept here)
 * @param status              monotonic spectrum detected/confirmed/dismissed/replaced
 * @param confirmedManifestId authoritative manifest row id stamped on confirmation
 *                            (null until confirmed)
 */
public record ReleaseCandidateInfo(
        Long id,
        String version,
        String channel,
        String detectedSource,
        LocalDateTime detectedAt,
        String payloadJson,
        String status,
        Long confirmedManifestId) {
}
