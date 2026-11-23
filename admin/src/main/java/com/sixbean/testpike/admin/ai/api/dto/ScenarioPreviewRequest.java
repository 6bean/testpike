package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Stage0 scenario preview request as submitted by the circle-selection UI
 * . Field names mirror the core internal contract
 * (ScenarioPreviewInput) one to one — the bridge matches by name.
 * <p>The operator is taken from the login session by the controller, never
 * from the body.
 *
 * @param batchId owning batch id (nullable for a single-line preview outside a batch)
 * @param seq row order inside the submission (0-based)
 * @param projectId owning project
 * @param requirementText requirement line original text (anchor source)
 * @param priority GeneratedCase convention 0=P0 1=P1 2=P2
 */
public record ScenarioPreviewRequest(Long batchId, int seq, long projectId,
                                     String requirementText, int priority) {
}
