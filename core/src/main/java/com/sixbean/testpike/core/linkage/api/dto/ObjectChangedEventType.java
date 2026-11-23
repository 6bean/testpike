package com.sixbean.testpike.core.linkage.api.dto;

/**
 * Object types that announce {@code changed} events on the shared event bus,
 * generalizing the family first opened by the requirement domain. The routing
 * key of every member is literally {@code {objectType}.changed} on exchange
 * {@code testpike.events}; consumers (edge detectors) bind the keys they care
 * about, and queues stay consumer-declared (same posture as
 * {@link com.sixbean.testpike.core.events.api.dto.RequirementEventType}).
 *
 * <p>{@link #REQUIREMENT} deliberately keeps the exact routing key the
 * requirement bus already publishes — the generic family folds that event into
 * the bus instead of inventing a second protocol.
 */
public enum ObjectChangedEventType {

    /** Test case save (payload token {@code case}). */
    CASE("case"),

    /** Requirement save (payload token {@code requirement}). */
    REQUIREMENT("requirement"),

    /** API contract change (payload token {@code api_spec}). */
    API_SPEC("api_spec"),

    /** Mock stub change (payload token {@code mock}). */
    MOCK("mock"),

    /** Document / PRD change (payload token {@code doc}). */
    DOC("doc"),

    /** Performance scenario change (payload token {@code perf_scenario}). */
    PERF_SCENARIO("perf_scenario"),

    /** Test plan change (payload token {@code plan}). */
    PLAN("plan"),

    /** Prompt template change (payload token {@code prompt_template}). */
    PROMPT_TEMPLATE("prompt_template"),

    /** Tag rename/merge (payload token {@code tag}). */
    TAG("tag"),

    /** Skill asset change (payload token {@code skill}). */
    SKILL("skill");

    private final String objectType;

    ObjectChangedEventType(String objectType) {
        this.objectType = objectType;
    }

    /** Bus token of this family member. */
    public String objectType() {
        return objectType;
    }

    /** Routing key under the shared events exchange: {@code {objectType}.changed}. */
    public String routingKey() {
        return objectType + ".changed";
    }
}
