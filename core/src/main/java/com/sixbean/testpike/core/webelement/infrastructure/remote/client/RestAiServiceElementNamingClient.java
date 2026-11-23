package com.sixbean.testpike.core.webelement.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ai-service survey-naming door, RestClient form (WE batch segment P3; the
 * RestAiServiceVisionClient family: RestClientInvoker reuse, no second HTTP
 * helper, downstream error codes rethrown at the same code — the naming
 * component above catches and degrades to rule-collected names).
 */
@Component
public class RestAiServiceElementNamingClient implements AiServiceElementNamingClient {

    private final RestClient rest;

    public RestAiServiceElementNamingClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl);
    }

    @Override
    public Map<String, String> nameCandidates(long projectId, String pageUrl, Long sessionId,
                                              List<Map<String, String>> descriptors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("pageUrl", pageUrl);
        body.put("candidates", descriptors);
        body.put("sessionId", sessionId);
        Map<String, Object> data = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/web-element/survey-naming", body,
                new ParameterizedTypeReference<>() {
                }, null);
        return namesOf(data);
    }

    private static Map<String, String> namesOf(Map<String, Object> data) {
        if (data == null || !(data.get("names") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String identity && value instanceof String name
                    && !name.isBlank()) {
                names.put(identity, name);
            }
        });
        return names;
    }
}
