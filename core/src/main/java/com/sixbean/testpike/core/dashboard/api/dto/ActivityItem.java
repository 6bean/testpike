package com.sixbean.testpike.core.dashboard.api.dto;


public record ActivityItem(long id, Long projectId, String eventType, String title,
                           String dedupKey, String createdAt) {
}
