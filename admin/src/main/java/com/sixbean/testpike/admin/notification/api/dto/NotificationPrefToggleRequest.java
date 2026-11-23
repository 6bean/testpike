package com.sixbean.testpike.admin.notification.api.dto;


public record NotificationPrefToggleRequest(String eventType, boolean enabled) {
}
