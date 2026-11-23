package com.sixbean.testpike.common.mq;

/**
 * MQ message for an AI mock-drafting job (AG batch , route key
 * {@code ai.mock.gen} on exchange {@code testpike.events}).
 * <p>Identity-only payload (AiSpecExtractMessage precedent): the prompt body
 * and the pinned budget snapshot stay on tp_mock_ai_job in core — the
 * ai-service consumer pulls them over the internal endpoint instead of
 * trusting a fat message, so a replayed message can never run against a stale
 * cost account or a deleted space.
 *
 * @param jobId tp_mock_ai_job row id (the single source of job truth)
 * @param spaceId target mock space (consumer re-checks existence)
 * @param projectId owning project (LLM routing + audit anchor, never guessed
 * from a request context across services)
 * @param jobType application point: nl-stub | edge-scenarios | spec-sync
 * @param operatorId who triggered the draft (audit passthrough)
 * @param publishToken per-trigger UUID; the consumer's duplicate-delivery dedup
 * key (jobId + token) — a re-fire means a fresh token
 */
public record MockGenMessage(long jobId, long spaceId, long projectId, String jobType,
                             long operatorId, String publishToken) {

    /** Route key of the mock-draft queue family. */
    public static final String ROUTING_KEY = "ai.mock.gen";
}
