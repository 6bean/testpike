package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record AppautoPlatformInfo(
        Long id,
        String code,
        String name,
        List<String> deviceKindCodes,
        List<String> carrierTypes,
        String note,
        int status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
