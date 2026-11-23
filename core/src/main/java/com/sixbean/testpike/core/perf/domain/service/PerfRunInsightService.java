package com.sixbean.testpike.core.perf.domain.service;

import java.util.Map;

/**
 * AI run-insight orchestration (B-11 : turn one finished load-test
 * run's attribution facts into a plain-word read-out through the model.
 * Validation, switch gating and facts assembly all have their single point in
 * the implementation — admin only forwards project and run anchors.
 */
public interface PerfRunInsightService {

    /**
     * Explain one run's attribution: switch gate first, then ownership and the
     * judge verdict (a run still pending is answered honestly with zero model
     * tokens), then one ai-service hop over the assembled facts block.
     * Nothing is persisted — the read-out is transient report-face material.
     */
    Map<String, Object> insight(long projectId, long runId);
}
