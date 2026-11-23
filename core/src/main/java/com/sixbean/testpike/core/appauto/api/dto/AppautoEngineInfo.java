package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


public record AppautoEngineInfo(
        Long id,
        String code,
        String name,
        List<String> platformCodes,
        List<String> deviceKindCodes,
        int defaultPriority,
        String runtimeType,
        Map<String, String> runtimeVersionReq,
        Map<String, Boolean> capabilities,
        List<String> driverRequirements,
        List<Map<String, String>> envCheckSpec,
        Map<String, Object> installRecipe,
        int status,
        String installDocUrl,
        String license,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
