package com.sixbean.testpike.admin.config.api.dto;

import java.time.LocalDateTime;


public record RetentionItemView(String configKey, String label, int days,
                                LocalDateTime cutoff, long count) {
}
