package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record AppautoDeviceView(
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

 
    public AppautoDeviceView(Long id, Long projectId, String name, String serial,
                             String deviceType, String adbAddress, String model,
                             String osVersion, String resolution, Integer dpi,
                             List<String> labels, String healthState, int consecutiveFailures,
                             LocalDateTime lastSeenAt, LocalDateTime lastOverheatAt,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, name, serial, deviceType, adbAddress, model, osVersion, resolution,
                dpi, labels, healthState, consecutiveFailures, lastSeenAt, lastOverheatAt,
                createdAt, updatedAt, null);
    }
}
