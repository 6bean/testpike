package com.sixbean.testpike.admin.cluster.api.dto;

import java.time.LocalDateTime;


public record ReleaseManifestView(
        Long id,
        String version,
        String componentMatrix,
        String breakingChanges,
        String executorVersionMin,
        String executorVersionMax,
        String suggestedPath,
        String source,
        LocalDateTime registeredAt,
        String artifacts,
        String releaseNotesMd,
        LocalDateTime publishedAt) {
}
