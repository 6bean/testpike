package com.sixbean.testpike.core.monitor.api.dto;

import java.time.LocalDateTime;


public record MonitorCheckLogView(
        LocalDateTime checkedAt,
        String outcome,
        Integer latencyMs,
        String detail) {
}
