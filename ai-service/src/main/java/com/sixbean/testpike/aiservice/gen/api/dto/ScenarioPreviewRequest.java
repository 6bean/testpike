package com.sixbean.testpike.aiservice.gen.api.dto;

/**
 * Request body of the stage0 scenario-preview internal door .
 * <p>Field names mirror the core leg (RestAiServiceScenarioPreviewClient) one
 * to one — the cross-service bridge matches by name, a rename on either side
 * silently degrades the preview to the disabled shape.
 *
 * @param projectId project for model routing
 * @param requirementText requirement line original text (anchor source)
 * @param priority GeneratedCase convention 0=P0 1=P1 2=P2
 * @param operatorId submitting user (fallback-notice context; core folds
 * a null onto 0 before sending)
 * @param taskId batch row correlation shown in notices and logs
 */
public record ScenarioPreviewRequest(long projectId, String requirementText, int priority,
                                     Long operatorId, long taskId) {
}
