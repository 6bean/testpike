package com.sixbean.testpike.core.ai.api.dto;

/**
 * Input for one stage0 scenario preview line : the
 * requirement text plus its place inside a batch submission so the
 * circle-selection UI can map rows back after a page reload.
 *
 * @param batchId owning batch id (logical reference, nullable for a
 * single-line preview outside a batch)
 * @param seq row order inside the submission (0-based)
 * @param projectId owning project
 * @param operatorId submitting user (fallback-notice context)
 * @param requirementText requirement line original text (anchor source)
 * @param priority GeneratedCase convention 0=P0 1=P1 2=P2
 */
public record ScenarioPreviewInput(Long batchId, int seq, long projectId,
                                   Long operatorId, String requirementText, int priority) {
}
