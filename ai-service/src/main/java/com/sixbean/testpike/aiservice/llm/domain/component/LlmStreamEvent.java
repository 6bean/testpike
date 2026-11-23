package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 * One streamed piece from the provider while a completion is still in flight
 * (AO batch dialog kernel, . The client hands these to the caller
 * as they arrive; the caller maps kind→SSE frame type (content→delta,
 * reason→reason). Heartbeat/terminal frames are the orchestrator's concern,
 * not the transport's.
 *
 * @param kind content / reason (constants on this class)
 * @param text the incremental piece itself
 */
public record LlmStreamEvent(String kind, String text) {

    public static final String KIND_CONTENT = "content";
    public static final String KIND_REASON = "reason";
}
