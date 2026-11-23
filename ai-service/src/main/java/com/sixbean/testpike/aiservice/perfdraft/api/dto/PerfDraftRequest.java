package com.sixbean.testpike.aiservice.perfdraft.api.dto;

/**
 * Load-test scenario drafting request (B-11; fields mirror the core-side
 * orchestration request one-to-one).
 * <p>Validation single-source stays in core: the wizard only saves a
 * human-confirmed scenario through the normal scenario door, so step-shape
 * verdicts (action/method/url domain) belong to core PerfStepActionGate —
 * this leg renders the prompt, calls the model and parses the reply, and must
 * never grow a second spec.
 *
 * @param projectId   project anchor (LLM routing + call-log cost attribution)
 * @param description one-sentence plain-language load-test intent from the wizard
 */
public record PerfDraftRequest(
        Long projectId,
        String description) {
}
