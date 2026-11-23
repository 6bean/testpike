package com.sixbean.testpike.aiservice.goldenreplay.api.dto;

/**
 * One golden-set route-axis replay request :
 * the domain's released template family is replayed through two model-route
 * rows so a planned model switch can be measured against the golden pack
 * before it ships. The template head stays the control variable — both sides
 * send byte-identical heads, only the model differs.
 *
 * @param domain prompt template domain whose family is replayed
 * @param templateName prompt template family name (its full-release row is
 * the single replayed template body)
 * @param controlModel current model route (control side)
 * @param candidateModel model route entering evaluation (candidate side);
 * must differ from the control or the comparison is refused
 * @param projectId project that owns the replay calls (billing/notice scoping)
 */
public record GoldenRouteReplayRequest(
        String domain,
        String templateName,
        String controlModel,
        String candidateModel,
        Long projectId) {
}
