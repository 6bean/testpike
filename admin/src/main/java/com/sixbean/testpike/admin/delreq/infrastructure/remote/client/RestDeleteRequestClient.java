package com.sixbean.testpike.admin.delreq.infrastructure.remote.client;

import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestCreateRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestRejectRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;


@Component
public class RestDeleteRequestClient implements DeleteRequestClient {

    private final RestClient rest;

    public RestDeleteRequestClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<DeleteRequestView> list(long projectId, Integer status, int page, int size,
                                            String sortBy, String sortDir, long operatorId) {
        StringBuilder uri = new StringBuilder("/internal/delete-requests?projectId=" + projectId
                + (status == null ? "" : "&status=" + status)
                + "&page=" + page + "&size=" + size);
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(UriUtils.encodeQueryParam(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(UriUtils.encodeQueryParam(sortDir, StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<Response<PageView<DeleteRequestView>>>() {
                }, operatorId);
    }

    @Override
    public DeleteRequestView create(long operatorId, DeleteRequestCreateRequest request) {
        return call(HttpMethod.POST, "/internal/delete-requests", request,
                new ParameterizedTypeReference<Response<DeleteRequestView>>() {
                }, operatorId);
    }

    @Override
    public DeleteRequestView get(long id, long operatorId) {
        return call(HttpMethod.GET, "/internal/delete-requests/" + id, null,
                new ParameterizedTypeReference<Response<DeleteRequestView>>() {
                }, operatorId);
    }

    @Override
    public DeleteRequestView approve(long id, long operatorId) {
        return call(HttpMethod.POST, "/internal/delete-requests/" + id + "/approve", null,
                new ParameterizedTypeReference<Response<DeleteRequestView>>() {
                }, operatorId);
    }

    @Override
    public DeleteRequestView reject(long id, long operatorId, DeleteRequestRejectRequest request) {
        return call(HttpMethod.POST, "/internal/delete-requests/" + id + "/reject", request,
                new ParameterizedTypeReference<Response<DeleteRequestView>>() {
                }, operatorId);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type, Long operatorId) {
        return RestClientInvoker.call(rest, method, uri, body, type, operatorId);
    }
}
