package com.sixbean.testpike.core.perf.infrastructure.remote.client;

import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;

import java.util.Map;

/**
 * ai-service scenario-drafting door client (B-11; AiServiceNlStepClient family
 * posture — the browser/admin never call ai-service directly, orchestration
 * always passes through core). The raw reply is unparsed here: step-shape
 * trimming is the core service's single point.
 */
public interface AiServicePerfDraftClient {

    /** One model hop -> draft {steps:[...]} as returned (errors rethrown with the downstream code). */
    Map<String, Object> draft(PerfScenarioDraftRequest request);
}
