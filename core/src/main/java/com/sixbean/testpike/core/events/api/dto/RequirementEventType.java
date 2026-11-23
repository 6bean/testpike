package com.sixbean.testpike.core.events.api.dto;

/**
 * Requirement event types (first requirement-family event on the shared topic
 * exchange). Routing keys are binding keys under exchange {@code testpike.events};
 * consumers declare their own queues (same posture as {@link CaseEventType}).
 */
public enum RequirementEventType {

    /**
     * A requirement row really changed (content edit or status transition).
     * Payload: requirementId / projectId / operatorId / requirementVersion.
     * Consumers: impact scan of linked cases and later knowledge-base ingestion
     * (idempotency key kb-ingest:req:{id}:{version}).
     */
    CHANGED("requirement.changed"),

    /**
     * An explicit submit-review granted PASS on the given requirement version.
     * Payload: requirementId / projectId / operatorId / requirementVersion.
     * This is the knowledge-base ingest door (idempotency key
     * kb-ingest:req:{id}:{version}): un-reviewed edits never announce it, which
     * keeps pending content out of grounding and seals the feedback loop.
     */
    REVIEW_PASSED("requirement.review-passed");

    private final String routingKey;

    RequirementEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
