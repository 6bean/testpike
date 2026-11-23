package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.Map;


public record AppautoEnvProfileInfo(
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
