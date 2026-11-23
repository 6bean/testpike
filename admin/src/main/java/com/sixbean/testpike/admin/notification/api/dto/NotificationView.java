package com.sixbean.testpike.admin.notification.api.dto;


public record NotificationView(long id, long userId, Long projectId, String eventType,
                               String channel, String title, String content,
                               int status, int readFlag, String createdAt, String dedupKey) {
}
