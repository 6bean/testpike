package com.sixbean.testpike.admin.sched.api.dto;


public record SchedulerHealthView(String jobName, String description, String lockedBy,
                                  String lockedAt, String lockUntil, boolean running) {
}
