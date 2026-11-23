package com.sixbean.testpike.admin.cluster.api.dto;


public record ReleaseRegisterRequest(
        String version,
        String componentMatrixJson,
        String breakingChangesJson,
        String executorVersionMin,
        String executorVersionMax,
        String suggestedPath,
        String source,
        String artifactsJson,
        String releaseNotesMd) {
}
