package com.sixbean.testpike.core.cluster.api.dto;

import java.time.LocalDateTime;

/**
 * Release manifest view (single row from tp_release_manifest; authoritative for upgrades).
 * Pass the JSON column through as ::text, with serialization/deserialization completed at a single point on the consumer side (following the AppautoEnvProfile convention).
 */
public record ReleaseManifestInfo(
        Long id,
        String version,
        String componentMatrix,
        String breakingChanges,
        String executorVersionMin,
        String executorVersionMax,
        String suggestedPath,
        String source,
        LocalDateTime registeredAt,
        // Artifact supply chain columns (download list [{component_family,url,
        // sha256,size}] as ::text passthrough, human release notes markdown,
        // publish stamp — consumers parse the jsonb string at a single point).
        String artifacts,
        String releaseNotesMd,
        LocalDateTime publishedAt) {
}
