package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoAppView(
        Long id,
        Long projectId,
        String name,
        String packageName,
        String version,
        String platformCode,
        String permissionRuleRef,
        String artifactKey,
        Long artifactSizeBytes,
        String artifactSha256,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
