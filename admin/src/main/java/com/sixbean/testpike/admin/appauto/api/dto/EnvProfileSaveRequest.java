package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.Map;


public record EnvProfileSaveRequest(
        Long projectId,
        String name,
        String profileKind,
        Map<String, Object> payload,
        String remark,
        Long loginRecipeAssetId) {
}
