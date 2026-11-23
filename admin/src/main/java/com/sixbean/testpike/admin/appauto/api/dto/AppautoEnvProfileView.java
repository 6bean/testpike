package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.Map;


public record AppautoEnvProfileView(
        Long id,
        Long projectId,
        String name,
        String profileKind,
        Map<String, Object> payload,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long loginRecipeAssetId) {
}
