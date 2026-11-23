package com.sixbean.testpike.admin.storage.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.storage.api.dto.StorageConfigSaveRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageConfigView;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.admin.storage.api.dto.StorageProbeResult;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestStorageConfigClient implements StorageConfigClient {

    private final RestClient rest;

    public RestStorageConfigClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public StorageConfigView get() {
        return call(HttpMethod.GET, "/internal/storage/config", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public StorageConfigView update(StorageConfigSaveRequest request) {
        return call(HttpMethod.PUT, "/internal/storage/config", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public StorageProbeResult probe(StorageProbeRequest request) {
        return call(HttpMethod.POST, "/internal/storage/probe", request,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
