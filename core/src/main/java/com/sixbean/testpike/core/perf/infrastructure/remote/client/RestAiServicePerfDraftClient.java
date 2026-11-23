package com.sixbean.testpike.core.perf.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * ai-service scenario-drafting door RestClient implementation (B-11 ;
 * RestAiServiceNlStepClient family posture: shared RestClientInvoker, downstream
 * error codes rethrown with the same code). Read budget is 820s for the model
 * leg (same posture as RestAiServicePerfInsightClient): every hop of the
 * request chain must stay at or above the 800s floor, and the value is
 * installation-configurable via ai.perf.draft-read-timeout-seconds.
 * <p>Path-join self-check: base-url + /internal/perf/draft is exactly the
 * ai-service PerfDraftInternalController route (live probe accompanies L3).
 */
@Component
public class RestAiServicePerfDraftClient implements AiServicePerfDraftClient {

    private final RestClient rest;

    public RestAiServicePerfDraftClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl,
            @Value("${ai.perf.draft-read-timeout-seconds:820}") long readTimeoutSeconds) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl,
                java.time.Duration.ofSeconds(readTimeoutSeconds));
    }

    @Override
    public Map<String, Object> draft(PerfScenarioDraftRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/perf/draft",
                request, new ParameterizedTypeReference<>() {
                }, null);
    }
}
