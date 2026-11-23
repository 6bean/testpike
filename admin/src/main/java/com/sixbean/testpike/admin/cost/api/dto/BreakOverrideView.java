package com.sixbean.testpike.admin.cost.api.dto;

import java.time.LocalDateTime;


public record BreakOverrideView(Long projectId, String reason, int hours,
                                LocalDateTime overrideUntil) {
}
