package com.sixbean.testpike.core.monitor.api.dto;

import java.util.Map;


public record MonitorTargetInput(
        String name,
        Long envId,
        String targetType,
        Map<String, Object> probeConfig,
        Integer checkIntervalSeconds,
        Boolean enabled) {
}
