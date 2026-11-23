package com.sixbean.testpike.core.project.api.dto;


public record ProjectEnvInfo(Long id, Long projectId, String name, int envType, String baseUrl,
                             String configJson, String endTypes, String browsers, int healthStatus,
                             java.time.LocalDateTime healthCheckedAt, String healthMsg,
                             String versionSnapshot, java.time.LocalDateTime createdAt,
                             java.time.LocalDateTime updatedAt) {
}
