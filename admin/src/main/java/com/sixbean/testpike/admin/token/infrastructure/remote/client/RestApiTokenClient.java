package com.sixbean.testpike.admin.token.infrastructure.remote.client;

import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreateRequest;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreatedView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenUsageView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestApiTokenClient implements ApiTokenClient {

    private final RestClient rest;

    public RestApiTokenClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<ApiTokenView> listByUser(long userId) {
        return call(HttpMethod.GET, "/internal/api-tokens", null,
                new ParameterizedTypeReference<Response<List<ApiTokenView>>>() {
                }, userId);
    }

    @Override
    public ApiTokenCreatedView create(long userId, ApiTokenCreateRequest request) {
        return call(HttpMethod.POST, "/internal/api-tokens", request,
                new ParameterizedTypeReference<Response<ApiTokenCreatedView>>() {
                }, userId);
    }

    @Override
    public void revoke(long id, long operatorId) {
        call(HttpMethod.DELETE, "/internal/api-tokens/" + id + "/revoke", null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, operatorId);
    }

    @Override
    public ApiTokenUsageView usage(long tokenId, long operatorId) {
        // core-side aggregation single source (S2b); the header carries the
        // logged-in operator so core decides ownership/read-through alone.
        return call(HttpMethod.GET, "/internal/mcp-tokens/" + tokenId + "/usage", null,
                new ParameterizedTypeReference<Response<ApiTokenUsageView>>() {
                }, operatorId);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type, Long operatorId) {
        return RestClientInvoker.call(rest, method, uri, body, type, operatorId);
    }
}
