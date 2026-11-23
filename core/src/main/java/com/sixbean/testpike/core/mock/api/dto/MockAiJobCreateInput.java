package com.sixbean.testpike.core.mock.api.dto;

/**
 * AI drafting job creation input . Only identity + prompt travel here:
 * the service validates gates, pins the budget snapshot, and persists the row;
 * the MQ dispatch is fired from the controller publish point, never
 * inside this call.
 *
 * @param spaceId target mock space (rule source stays space-scoped)
 * @param jobType application point: nl-stub | edge-scenarios | spec-sync |
 * llm-fallback | record-summarize (M10 types land with V241 —
 * accepted only when the master gate ANDs with their sub-switch)
 * @param prompt the natural-language drafting input (never blank)
 * @param recordingId cassette box the A-4 induction job must summarize — required
 * exactly when jobType is record-summarize (the service refuses
 * an open/empty box or a foreign space in plain words) and
 * refused as a stray input for every other point
 */
public record MockAiJobCreateInput(
        long spaceId,
        String jobType,
        String prompt,
        Long recordingId) {

    /** Drafting points never bind a cassette — kept so pre-A-4 callers stay source-compatible. */
    public MockAiJobCreateInput(long spaceId, String jobType, String prompt) {
        this(spaceId, jobType, prompt, null);
    }
}
