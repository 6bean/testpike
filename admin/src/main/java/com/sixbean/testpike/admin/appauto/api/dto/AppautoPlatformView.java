package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record AppautoPlatformView(
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
