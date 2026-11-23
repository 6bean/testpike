package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowLlmRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestAiServiceRecordFlowClient implements AiServiceRecordFlowClient {

    private final RestClient rest;

    public RestAiServiceRecordFlowClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generate(AppautoRecordFlowLlmRequest request) {
        Map<String, Object> draft = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/record-flow", request,
                new ParameterizedTypeReference<>() {
                }, null);
        Object steps = draft == null ? null : draft.get("steps");
        return steps instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
