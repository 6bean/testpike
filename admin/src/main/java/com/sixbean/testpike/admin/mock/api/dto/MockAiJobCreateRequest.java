package com.sixbean.testpike.admin.mock.api.dto;

/**
 * AI drafting job creation request (admin bridge, ). Field-for-field
 * mirror of the core MockAiJobCreateInput record — the bridge adds no
 * validation: gate order and plain-word refusals are the core service's
 * single point (red line 6).
 *
 * @param spaceId target mock space
 * @param jobType application point: nl-stub | edge-scenarios | spec-sync |
 * llm-fallback | record-summarize (M10 types land with V241;
 * queue-side triage of the non-drafting points lives in the
 * ai-service consumer, the gate spectrum stays in core)
 * @param prompt natural-language drafting input (never blank)
 * @param recordingId cassette box to summarize (record-summarize only — the
 * space/ghost/open/empty refusals are validated in core,
 * the bridge mirrors the field and adds no checks)
 */
public record MockAiJobCreateRequest(
        long spaceId,
        String jobType,
        String prompt,
        Long recordingId) {
}
