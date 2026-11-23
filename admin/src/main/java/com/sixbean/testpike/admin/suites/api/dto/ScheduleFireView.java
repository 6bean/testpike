package com.sixbean.testpike.admin.suites.api.dto;


public record ScheduleFireView(long id, String kind, long refId, long projectId,
                               String fireTime, String status, String message, String createdAt) {
}
