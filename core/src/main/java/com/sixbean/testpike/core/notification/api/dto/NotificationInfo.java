package com.sixbean.testpike.core.notification.api.dto;


public record NotificationInfo(long id, long userId, Long projectId, String eventType,
                               String channel, String title, String content,
                               int status, int readFlag, String createdAt, String dedupKey) {
}
