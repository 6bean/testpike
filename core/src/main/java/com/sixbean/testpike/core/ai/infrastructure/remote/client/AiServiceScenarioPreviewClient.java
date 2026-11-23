package com.sixbean.testpike.core.ai.infrastructure.remote.client;

import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewResult;

/**
 * core → ai-service stage0 scenario enumeration leg .
 * <p>The enumeration is a model-heavy reasoning consumer, so it lives in
 * ai-service behind the shared kernel deliberation ring; core owns the
 * preview ledger and the cost accounting. Tests isolate this seam at L2;
 * the live chain is verified by the L3 probe against
 * {@code POST /internal/gen/scenario-preview}.
 */
public interface AiServiceScenarioPreviewClient {

    /**
     * Enumerates scenarios for one requirement line.
     *
     * @param projectId       project for model routing
     * @param requirementText requirement line original text (anchor source)
     * @param priority        GeneratedCase convention 0=P0 1=P1 2=P2
     * @param operatorId      submitting user (fallback-notice context)
     * @param correlationId   batch row correlation shown in notices and logs
     *                        (seq inside the submission; no task exists yet)
     */
    ScenarioPreviewResult enumerate(long projectId, String requirementText, int priority,
                                    Long operatorId, long correlationId);
}
