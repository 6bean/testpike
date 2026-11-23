package com.sixbean.testpike.core.ai.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * RestClient implementation of the core → ai-service stage0 enumeration leg
 * ; RestAiServiceMockLlmFallbackClient posture: shared
 * RestClientInvoker so downstream error codes re-throw in business semantics
 * instead of being swallowed into 50000; timeout = the per-leg forward budget
 * key (constructor), sized above the downstream LLM read budget.
 * never stretched — one preview is one deliberation ring, not a retry storm).
 * <p>Path guard: the URI mirrors ai-service's ScenarioPreviewInternalController
 * (class-level /internal/gen + method-level /scenario-preview) character by
 * character; the live full-path probe rides the L3 window.
 */
@Component
public class RestAiServiceScenarioPreviewClient implements AiServiceScenarioPreviewClient {

    private final RestClient rest;

    public RestAiServiceScenarioPreviewClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl,
            @Value("${ai.scenario-preview.forward-read-timeout-seconds:820}") long forwardReadTimeoutSeconds) {
        // Same forward budget key on both bridge legs: the downstream call is one real
        // LLM enumeration (ai-service read budget 300s), so this leg must not inherit
        // the 30s default bridge timeout (E2E window w2 evidence).
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl,
                java.time.Duration.ofSeconds(forwardReadTimeoutSeconds));
    }

    @Override
    public ScenarioPreviewResult enumerate(long projectId, String requirementText, int priority,
                                           Long operatorId, long correlationId) {
        Map<String, Object> body = Map.of(
                "projectId", projectId,
                "requirementText", requirementText,
                "priority", priority,
                "operatorId", operatorId == null ? 0L : operatorId,
                "taskId", correlationId);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/gen/scenario-preview",
                body, new ParameterizedTypeReference<Response<ScenarioPreviewResult>>() {
                }, null);
    }
}
