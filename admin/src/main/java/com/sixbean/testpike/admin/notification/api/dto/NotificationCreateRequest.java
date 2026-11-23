package com.sixbean.testpike.admin.notification.api.dto;


public record NotificationCreateRequest(long userId, Long projectId, String eventType,
                                        String title, String content, String dedupKey) {
}
