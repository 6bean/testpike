package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record AppautoDeviceInfo(
        Long id,
        Long projectId,
        String name,
        String serial,
        String deviceType,
        String adbAddress,
        String model,
        String osVersion,
        String resolution,
        Integer dpi,
        List<String> labels,
        String healthState,
        int consecutiveFailures,
        LocalDateTime lastSeenAt,
        LocalDateTime lastOverheatAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String platform) {
}
