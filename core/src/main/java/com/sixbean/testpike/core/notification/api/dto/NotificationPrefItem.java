package com.sixbean.testpike.core.notification.api.dto;


public record NotificationPrefItem(String eventType, boolean enabled, boolean forced,
                                   boolean defaultOff, String updatedAt) {
}
