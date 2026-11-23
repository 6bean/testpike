package com.sixbean.testpike.admin.config.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.config.api.dto.ConfigSaveRequest;
import com.sixbean.testpike.admin.config.api.dto.ConfigView;
import com.sixbean.testpike.admin.config.api.dto.RetentionItemView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;


@Component
public class RestConfigClient implements ConfigClient {

    private final RestClient rest;

    public RestConfigClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<ConfigView> list(String groupName) {
        String uri = UriComponentsBuilder.fromPath("/internal/configs")
                .queryParamIfPresent("groupName", Optional.ofNullable(groupName))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ConfigView create(ConfigSaveRequest request) {
        return call(HttpMethod.POST, "/internal/configs", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ConfigView update(long id, ConfigSaveRequest request) {
        return call(HttpMethod.PUT, "/internal/configs/" + id, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/configs/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<RetentionItemView> retentionPreview() {
        return call(HttpMethod.POST, "/internal/configs/retention/preview", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<RetentionItemView> retentionRun() {
        return call(HttpMethod.POST, "/internal/configs/retention/run", null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
