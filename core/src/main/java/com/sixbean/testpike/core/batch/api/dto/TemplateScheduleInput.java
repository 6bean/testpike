package com.sixbean.testpike.core.batch.api.dto;

/**
 * Template schedule configuration input parameters (PUT /batches/templates/{id}/schedule).
 *
 * @param cronExpr Cron expression for scheduled reuse (Spring 6-field; required when enabled and may be cleared when disabled).
 * @param enabled 1=schedule enabled, 0=disabled.
 * @param scheduleEnvId Target environment for scheduled reuse (required when enabled; must be pinned in advance for unattended execution and may be cleared when disabled).
 */
public record TemplateScheduleInput(String cronExpr, Integer enabled, Long scheduleEnvId) {
}
