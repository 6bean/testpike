package com.sixbean.testpike.admin.monitor.api.dto;

import java.util.Map;


public record TargetSaveRequest(
        Long projectId,
        Long envId,
        String name,
        String targetType,
        Map<String, Object> probeConfig,
        Integer checkIntervalSeconds,
        Boolean enabled) {
}
