package com.sixbean.testpike.core.perf.infrastructure.remote.client;

import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;

import java.util.Map;

/**
 * ai-service run-insight door client (B-11; AiServicePerfDraftClient family
 * posture — the browser/admin never call ai-service directly, orchestration
 * always passes through core). The raw reply is unparsed here: the insight
 * text rides back as the ai leg produced it, format refusals are that leg's
 * own three-shape contract and core never re-judges the answer.
 */
public interface AiServicePerfInsightClient {

    /** One model hop -> {insight:"..."} as returned (errors rethrown with the downstream code). */
    Map<String, Object> explain(PerfRunInsightRequest request);
}
