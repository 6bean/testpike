package com.sixbean.testpike.core.linkage.api.dto;

import java.util.List;

/**
 * Generic object-changed announcement (plain JSON record, cross-language
 * parsable — same posture as
 * {@link com.sixbean.testpike.core.events.api.dto.RequirementEventMessage}).
 * The field diff rides along so the edge trigger-field predicate can arm
 * without a second read of the upstream row.
 *
 * @param eventType      {@link ObjectChangedEventType} enum name (never free text)
 * @param objectId       the object the event is about (never null)
 * @param projectId      owning project (H1 isolation dimension; null=system-level asset)
 * @param operatorId     user who caused the change
 * @param objectVersion  row version after the change, consumer idempotency anchor
 * @param changedFields  field names the save actually changed (empty=unknown/full diff)
 * @param occurredAt     epoch millis of the event
 */
public record ObjectChangedEventMessage(
        String eventType,
        Long objectId,
        Long projectId,
        Long operatorId,
        Integer objectVersion,
        List<String> changedFields,
        Long occurredAt) {
}
