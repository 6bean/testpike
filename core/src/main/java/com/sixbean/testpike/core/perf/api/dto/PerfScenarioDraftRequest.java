package com.sixbean.testpike.core.perf.api.dto;

/**
 * Scenario-draft request on the core orchestration leg (B-11 :
 * carries the wizard's one-sentence load-test intent down to the ai-service
 * drafting door. Field names mirror the ai-service PerfDraftRequest so the
 * RestClient bridge forwards without translation.
 *
 * @param projectId project anchor (LLM routing + cost attribution)
 * @param description one-sentence plain-language load-test intent
 */
public record PerfScenarioDraftRequest(
        Long projectId,
        String description) {
}
