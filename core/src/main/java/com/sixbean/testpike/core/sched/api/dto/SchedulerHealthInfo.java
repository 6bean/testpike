package com.sixbean.testpike.core.sched.api.dto;


public record SchedulerHealthInfo(String jobName, String description, String lockedBy,
                                  String lockedAt, String lockUntil, boolean running) {
}
