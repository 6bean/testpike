package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockLlmRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;


@Component
public class RestAiServiceWxMockClient implements AiServiceWxMockClient {

    private final RestClient rest;

    public RestAiServiceWxMockClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl);
    }

    @Override
    public Map<String, Object> generate(AppautoWxMockLlmRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/wx-mock", request,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
