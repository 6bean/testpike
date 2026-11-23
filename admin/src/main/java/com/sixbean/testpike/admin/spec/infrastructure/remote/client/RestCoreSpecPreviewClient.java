package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecPreviewView;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractDispatchRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractEstimateView;
import com.sixbean.testpike.admin.spec.api.dto.SpecPreviewApplyView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


@Component
public class RestCoreSpecPreviewClient implements CoreSpecPreviewClient {

    private final RestClient rest;

    public RestCoreSpecPreviewClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ApiSpecPreviewView generate(long specId) {
        return call(HttpMethod.POST, "/internal/api-spec-previews/generate/" + specId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ApiSpecPreviewView get(long previewId) {
        return call(HttpMethod.GET, "/internal/api-spec-previews/" + previewId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ApiSpecPreviewView latestByDocument(long documentId) {
        return call(HttpMethod.GET, "/internal/api-spec-previews/by-document/" + documentId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SpecPreviewApplyView apply(long previewId, List<String> selectedKeys) {
        return call(HttpMethod.POST, "/internal/api-spec-previews/" + previewId + "/apply",
                Map.of("selectedKeys", selectedKeys == null ? List.of() : selectedKeys),
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SpecExtractEstimateView dispatchExtract(SpecExtractDispatchRequest request) {
        return call(HttpMethod.POST, "/internal/api-spec-previews/extract-from-document",
                Map.of("documentId", request.documentId(),
                        "modelHint", request.modelHint() == null ? "" : request.modelHint()),
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
