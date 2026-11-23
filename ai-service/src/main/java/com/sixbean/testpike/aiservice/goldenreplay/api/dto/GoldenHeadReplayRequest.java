package com.sixbean.testpike.aiservice.goldenreplay.api.dto;

/**
 * One golden-set head-axis replay request :
 * the domain's released template family is replayed through the old and the
 * new assembler head (the bare template body vs the kernel-prefixed
 * production head) over one pinned model, so an assembler-segment change is
 * measured against the golden pack before it ships. The model stays the
 * control variable — both sides call the same route, only the head shape
 * differs.
 *
 * @param domain prompt template domain whose family is replayed
 * @param templateName prompt template family name (its full-release row is
 * the single replayed template body)
 * @param projectId project that owns the replay calls (billing/notice scoping)
 * @param modelHint explicit model pin; blank falls back to project routing.
 * Both sides always run on the SAME resolved model — the
 * control variable here is the head, never the model.
 */
public record GoldenHeadReplayRequest(
        String domain,
        String templateName,
        Long projectId,
        String modelHint) {
}
