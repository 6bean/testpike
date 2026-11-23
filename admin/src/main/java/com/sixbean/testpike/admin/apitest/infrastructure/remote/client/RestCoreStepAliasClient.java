package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.StepAliasSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.StepAliasView;
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
public class RestCoreStepAliasClient implements CoreStepAliasClient {

    private static final String BASE = "/internal/apitest/step-aliases";

    private final RestClient rest;

    public RestCoreStepAliasClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public StepAliasView createAlias(StepAliasSaveRequest request) {
        return call(HttpMethod.POST, BASE, request, new ParameterizedTypeReference<Response<StepAliasView>>() {
        });
    }

    @Override
    public StepAliasView updateAlias(long id, StepAliasUpdateRequest request) {
        return call(HttpMethod.PUT, BASE + "/" + id, request,
                new ParameterizedTypeReference<Response<StepAliasView>>() {
                });
    }

    @Override
    public void deleteAlias(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null, new ParameterizedTypeReference<Response<Void>>() {
        });
    }

    @Override
    public List<StepAliasView> listAliases(long projectId) {
        return call(HttpMethod.GET, BASE + "?projectId=" + projectId, null,
                new ParameterizedTypeReference<Response<List<StepAliasView>>>() {
                });
    }

    @Override
    public StepAliasView findAlias(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<StepAliasView>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
