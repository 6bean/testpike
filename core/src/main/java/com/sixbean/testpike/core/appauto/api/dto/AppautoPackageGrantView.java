package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoPackageGrantView(
        Long appId,
        Long projectId,
        String packageName,
        String version,
        String fileName,
        String downloadUrl,
        String sha256,
        Long sizeBytes) {
}
