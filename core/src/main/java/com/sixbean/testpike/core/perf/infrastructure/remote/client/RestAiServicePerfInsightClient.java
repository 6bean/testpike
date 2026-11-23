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
 * error codes rethrown with the same code). Read budget is 820s: this hop
 * carries the model leg, and every hop of the request chain must stay at or
 * above the 800s floor (a small cap on one hop disables the whole chain —
 * the earlier 90s widening still starved slow model legs). The inner leg
 * keeps the smaller budget, so its honest refusal still rides through
 * untranslated. The value is hot-reloadable per installation
 * (ai.perf.insight-read-timeout-seconds).
 * <p>Path-join self-check: base-url + /internal/perf/insight is exactly the
 * ai-service PerfInsightInternalController route (live probe accompanies L3).
 */
@Component
public class RestAiServicePerfInsightClient implements AiServicePerfInsightClient {

    private final RestClient rest;

    public RestAiServicePerfInsightClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl,
            @Value("${ai.perf.insight-read-timeout-seconds:820}") long readTimeoutSeconds) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl,
                java.time.Duration.ofSeconds(readTimeoutSeconds));
    }

    @Override
    public Map<String, Object> explain(PerfRunInsightRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/perf/insight",
                request, new ParameterizedTypeReference<>() {
                }, null);
    }
}
