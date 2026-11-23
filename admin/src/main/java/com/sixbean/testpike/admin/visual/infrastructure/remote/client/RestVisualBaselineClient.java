package com.sixbean.testpike.admin.visual.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.visual.api.dto.VisualBaselineView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestVisualBaselineClient implements VisualBaselineClient {

    private final RestClient rest;

    public RestVisualBaselineClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<VisualBaselineView> listByCase(long projectId, long caseId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/visual-baselines?projectId=" + projectId + "&caseId=" + caseId, null,
                new ParameterizedTypeReference<Response<List<VisualBaselineView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public VisualBaselineView getById(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/visual-baselines/" + id, null,
                new ParameterizedTypeReference<Response<VisualBaselineView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public VisualBaselineView confirm(long id) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/visual-baselines/" + id + "/confirm", null,
                new ParameterizedTypeReference<Response<VisualBaselineView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public void reject(long id) {
        RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/visual-baselines/" + id + "/reject", null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public String viewUrl(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/visual-baselines/" + id + "/view-url", null,
                new ParameterizedTypeReference<Response<String>>() {
                }, UserContext.currentUserId());
    }
}
