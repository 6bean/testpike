package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;

import java.util.Map;

/**
 * Core orchestration service for AI load-test scenario drafting (B-11 .
 * <p>Single owning leg of the drafting path: switch gate → ai-service hop →
 * step re-validation/trimming. The result is a draft for the wizard prefill —
 * nothing is persisted; saving stays behind the human-confirmed scenario door.
 */
public interface PerfScenarioDraftService {

    /** Draft {steps, droppedCount, droppedReasons}; steps already carry core's step-gate verdict. */
    Map<String, Object> draft(PerfScenarioDraftRequest request);
}
