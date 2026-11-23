package com.sixbean.testpike.core.monitor.api.dto;

import java.time.LocalDateTime;


public record MonitorTargetInfo(
        Long id,
        Long projectId,
        Long envId,
        String name,
        String targetType,
        String probeConfigJson,
        Integer checkIntervalSeconds,
        boolean enabled,
        LocalDateTime silenceUntil,
        String silenceReason,
        String lastState,
        LocalDateTime lastCheckAt,
        String lastCause,
        Integer backoffLevel,
        Integer consecutiveFailures) {
}
