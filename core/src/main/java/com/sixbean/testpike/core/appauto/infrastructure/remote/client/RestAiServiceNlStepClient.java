package com.sixbean.testpike.core.appauto.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestAiServiceNlStepClient implements AiServiceNlStepClient {

    private final RestClient rest;

    public RestAiServiceNlStepClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl);
    }

    @Override
    public AppautoNlStepView generate(AppautoNlStepLlmRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/appauto/nl-step",
                request, new ParameterizedTypeReference<>() {
                }, null);
    }
}
