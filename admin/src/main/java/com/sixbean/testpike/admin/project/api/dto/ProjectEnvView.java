package com.sixbean.testpike.admin.project.api.dto;

import java.time.LocalDateTime;


public record ProjectEnvView(Long id, Long projectId, String name, int envType, String baseUrl,
                             String configJson, String endTypes, String browsers, int healthStatus,
                             LocalDateTime healthCheckedAt, String healthMsg,
                             String versionSnapshot, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
