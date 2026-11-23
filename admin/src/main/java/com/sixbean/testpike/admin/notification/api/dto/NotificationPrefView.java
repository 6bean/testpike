package com.sixbean.testpike.admin.notification.api.dto;


public record NotificationPrefView(String eventType, boolean enabled, boolean forced,
                                   boolean defaultOff, String updatedAt) {
}
