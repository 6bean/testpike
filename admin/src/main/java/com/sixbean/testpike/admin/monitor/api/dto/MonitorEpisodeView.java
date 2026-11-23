package com.sixbean.testpike.admin.monitor.api.dto;

import java.time.LocalDateTime;


public record MonitorEpisodeView(
        long episodeId,
        long targetId,
        String targetName,
        String targetType,
        long envId,
        LocalDateTime startedAt,
        LocalDateTime recoveredAt,
        boolean ongoing,
        int failureCount,
        String causeSummary) {
}
