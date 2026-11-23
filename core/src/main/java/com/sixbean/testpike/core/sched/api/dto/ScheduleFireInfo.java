package com.sixbean.testpike.core.sched.api.dto;


public record ScheduleFireInfo(long id, String kind, long refId, long projectId,
                               String fireTime, String status, String message, String createdAt) {
}
