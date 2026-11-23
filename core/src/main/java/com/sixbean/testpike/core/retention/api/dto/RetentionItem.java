package com.sixbean.testpike.core.retention.api.dto;

import java.time.LocalDateTime;


public record RetentionItem(
        String configKey,
        String label,
        int days,
        LocalDateTime cutoff,
        long count) {
}
