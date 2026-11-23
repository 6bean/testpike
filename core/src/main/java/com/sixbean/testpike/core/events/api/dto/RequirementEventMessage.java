package com.sixbean.testpike.core.events.api.dto;

/**
 * Requirement event message (plain JSON record, cross-language parsable —
 * same posture as {@link CaseEventMessage}).
 *
 * @param eventType           event type name ({@link RequirementEventType})
 * @param requirementId       the requirement the event is about (never null)
 * @param projectId           owning project (H1 isolation dimension, never null)
 * @param operatorId          user who caused the change
 * @param requirementVersion  row version after the change — carries the consumer
 *                            idempotency key kb-ingest:req:{id}:{version}
 * @param occurredAt          epoch millis of the event
 */
public record RequirementEventMessage(
        String eventType,
        Long requirementId,
        Long projectId,
        Long operatorId,
        Integer requirementVersion,
        Long occurredAt) {
}
