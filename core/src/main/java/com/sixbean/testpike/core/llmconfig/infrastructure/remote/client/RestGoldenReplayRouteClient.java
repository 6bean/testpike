package com.sixbean.testpike.core.llmconfig.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RestClient implementation of the core → ai-service route-axis golden replay
 * leg 3; RestAiServiceScenarioPreviewClient posture: shared
 * RestClientInvoker so downstream refusals re-throw in business semantics
 * instead of being swallowed into 50000).
 * <p>Timeout = its own forward-budget key, sized above a full golden replay
 * (the leg fires on a dedicated daemon thread, never on a user request path).
 * Path guard: the URI mirrors GoldenReplayInternalController
 * (class-level /internal/golden-replay + method-level /run-route) character by
 * character; the live full-path probe rides the L3 window.
 */
@Component
public class RestGoldenReplayRouteClient implements GoldenReplayRouteClient {

    private final RestClient rest;

    public RestGoldenReplayRouteClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl,
            @Value("${ai.golden-replay.route.forward-read-timeout-seconds:900}") long forwardReadTimeoutSeconds) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl,
                java.time.Duration.ofSeconds(forwardReadTimeoutSeconds));
    }

    @Override
    public void runRouteReplay(String domain, String templateName, String controlModel,
                               String candidateModel, long projectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", domain);
        body.put("templateName", templateName);
        body.put("controlModel", controlModel);
        body.put("candidateModel", candidateModel);
        body.put("projectId", projectId);
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/golden-replay/run-route",
                body, new ParameterizedTypeReference<Response<Map<String, Object>>>() {
                }, null);
    }
}
