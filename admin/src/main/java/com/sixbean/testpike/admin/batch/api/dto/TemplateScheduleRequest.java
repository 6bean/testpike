package com.sixbean.testpike.admin.batch.api.dto;


public record TemplateScheduleRequest(String cronExpr, Integer enabled, Long scheduleEnvId) {
}
