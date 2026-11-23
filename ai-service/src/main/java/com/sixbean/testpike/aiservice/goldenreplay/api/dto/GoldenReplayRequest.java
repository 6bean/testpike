package com.sixbean.testpike.aiservice.goldenreplay.api.dto;

/**
 * One golden-set drift replay request (internal face, ai-service).
 *
 * @param domain             prompt template domain whose family is version-compared
 * @param templateName       prompt template family name
 * @param candidateVersionId pinned template row entering evaluation (gray version)
 * @param controlVersionId   control row (current full release); null resolves the
 *                           newest full-release row of the family server-side
 * @param projectId          project whose LLM routing owns the replay calls
 * @param modelHint          explicit model pin; blank falls back to project routing.
 *                           Both sides of every pair always run on the SAME resolved
 *                           model (a model swap mid-comparison would poison the
 *                           control variable, so the fallback chain is pinned).
 */
public record GoldenReplayRequest(
        String domain,
        String templateName,
        Long candidateVersionId,
        Long controlVersionId,
        Long projectId,
        String modelHint) {
}
