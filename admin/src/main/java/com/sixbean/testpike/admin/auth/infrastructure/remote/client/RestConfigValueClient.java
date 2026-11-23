package com.sixbean.testpike.admin.auth.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestConfigValueClient implements ConfigValueClient {

    private final RestClient rest;

    public RestConfigValueClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public String getValue(String key, String defaultValue) {
        try {
            return RestClientInvoker.call(rest, HttpMethod.GET,
                    "/internal/configs/value?key=" + key + "&defaultValue=" + defaultValue, null,
                    new ParameterizedTypeReference<>() {
                    }, null);
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }
}
