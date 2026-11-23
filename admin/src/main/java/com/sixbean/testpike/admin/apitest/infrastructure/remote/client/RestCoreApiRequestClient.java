package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.AuthPresetDto;
import com.sixbean.testpike.admin.apitest.api.dto.EffectivePreviewDto;
import com.sixbean.testpike.admin.apitest.api.dto.PromotionResultDto;
import com.sixbean.testpike.admin.apitest.api.dto.RequestSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RequestView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestCoreApiRequestClient implements CoreApiRequestClient {

    private static final String BASE = "/internal/apitest/requests";

    private final RestClient rest;

    public RestCoreApiRequestClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public RequestView createRequest(RequestSaveRequest request) {
        return call(HttpMethod.POST, BASE, request,
                new ParameterizedTypeReference<Response<RequestView>>() {
                });
    }

    @Override
    public RequestView updateRequest(long id, RequestSaveRequest request) {
        return call(HttpMethod.PUT, BASE + "/" + id, request,
                new ParameterizedTypeReference<Response<RequestView>>() {
                });
    }

    @Override
    public void deleteRequest(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public List<RequestView> listRequests(long collectionId) {
        return call(HttpMethod.GET, BASE + "?collectionId=" + collectionId, null,
                new ParameterizedTypeReference<Response<List<RequestView>>>() {
                });
    }

    @Override
    public void reorderRequests(long collectionId, List<Long> orderedRequestIds) {
        call(HttpMethod.PUT, BASE + "/" + collectionId + "/order", orderedRequestIds,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public RequestView getRequest(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<RequestView>>() {
                });
    }

    @Override
    public EffectivePreviewDto effectivePreview(long id) {
        return call(HttpMethod.GET, BASE + "/" + id + "/effective", null,
                new ParameterizedTypeReference<Response<EffectivePreviewDto>>() {
                });
    }

    @Override
    public List<EffectivePreviewDto> effectivePreviewBatch(long collectionId) {
        return call(HttpMethod.GET, BASE + "/effective-batch?collectionId=" + collectionId, null,
                new ParameterizedTypeReference<Response<List<EffectivePreviewDto>>>() {
                });
    }

    @Override
    public PromotionResultDto promoteRequest(long id) {
        return call(HttpMethod.POST, BASE + "/" + id + "/promote", null,
                new ParameterizedTypeReference<Response<PromotionResultDto>>() {
                });
    }

    @Override
    public List<AuthPresetDto> listAuthPresets() {
        return call(HttpMethod.GET, BASE + "/auth-presets", null,
                new ParameterizedTypeReference<Response<List<AuthPresetDto>>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
