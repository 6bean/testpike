package com.sixbean.testpike.aiservice.gen.domain.service;

import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioEnumerationResult;

/**
 * Stage0 scenario enumeration of the generation chain (, ruling section 3
 * item 3): turns one requirement line into a bounded scenario list (main flow /
 * equivalence / counter example / boundary / state combination) that feeds the
 * human circle-selection step — the list is a preview, never a persisted case.
 * Case generation stays N requirement lines → N selected scenarios → N cases.
 */
public interface ScenarioEnumerationService {

    /**
     * Enumerates scenarios for one requirement line through the shared kernel
     * deliberation ring (propose → verify against the contract and the priority
     * strategy → targeted revise). The K8 switch
     * {@code ai.gen.scenario-expand.enabled} gates the whole feature: off means
     * zero model calls and the legacy one-case-per-line shape on the result.
     *
     * @param requirementText requirement line original text (anchor source)
     * @param priority        requirement priority, GeneratedCase convention 0=P0 1=P1 2=P2
     * @param projectId       project for model routing
     * @param operatorId      task operator (fallback-notice context)
     * @param taskId          owning AI task id (notice title and logs)
     */
    ScenarioEnumerationResult enumerate(String requirementText, int priority, long projectId,
                                        Long operatorId, long taskId);
}
