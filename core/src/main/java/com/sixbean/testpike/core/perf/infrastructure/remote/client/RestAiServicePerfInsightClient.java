package com.sixbean.testpike.core.perf.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * ai-service run-insight door RestClient implementation (B-11 ;
 * RestAiServicePerfDraftClient family: shared RestClientInvoker, downstream
 * error codes rethrown with the same code). Read budget is widened to 90s for
 * the model leg: a live run showed one real read-out can exceed the default
 * 30s bridge budget (vision close-out 2026-10-26), and the inner leg must
 * time out first so its honest refusal still rides through untranslated.
 * <p>Path-join self-check: base-url + /internal/perf/insight is exactly the
 * ai-service PerfInsightInternalController route (live probe accompanies L3).
 */
@Component
public class RestAiServicePerfInsightClient implements AiServicePerfInsightClient {

    private final RestClient rest;

    public RestAiServicePerfInsightClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl, java.time.Duration.ofSeconds(90));
    }

    @Override
    public Map<String, Object> explain(PerfRunInsightRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/perf/insight",
                request, new ParameterizedTypeReference<>() {
                }, null);
    }
}
