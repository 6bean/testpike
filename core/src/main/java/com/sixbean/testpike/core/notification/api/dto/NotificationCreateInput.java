package com.sixbean.testpike.core.notification.api.dto;


public record NotificationCreateInput(long userId, Long projectId, String eventType,
                                      String title, String content, String dedupKey) {
}
