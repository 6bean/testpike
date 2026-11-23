package com.sixbean.testpike.core.mock.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateInput;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateReply;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.sixbean.testpike.common.clientobject.Response;

/**
 * RestClient implementation of the core → ai-service A-3 generation leg
 * ; RestAiServiceWxMockClient posture: shared RestClientInvoker
 * so downstream error codes re-throw in business semantics instead of being
 * swallowed into 50000, timeout = the default client's, never stretched —
 * one escalation is one model call).
 * Path guard: the URI below mirrors ai-service's MockLlmFallbackInternalController
 * (class-level /internal/mock/llm-fallback + method-level /generate) character
 * by character; the live full-path probe rides L3.
 */
@Component
public class RestAiServiceMockLlmFallbackClient implements AiServiceMockLlmFallbackClient {

    private final RestClient rest;

    public RestAiServiceMockLlmFallbackClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl);
    }

    @Override
    public MockLlmFallbackGenerateReply generate(MockLlmFallbackGenerateInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/mock/llm-fallback/generate", input,
                new ParameterizedTypeReference<Response<MockLlmFallbackGenerateReply>>() {
                }, null);
    }
}
