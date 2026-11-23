package com.sixbean.testpike.admin.dashboard.api.dto;


public record ActivityItemView(long id, Long projectId, String eventType, String title,
                               String dedupKey, String createdAt) {
}
