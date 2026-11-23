package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.RunSetCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetDetailView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPageView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPreviewStepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RestClient implementation of the collection-run client .
 * X-Operator-Id passthrough for audit closure; 400/404 semantics preserved by
 * RestClientInvoker.call (shared single point — no duplicated call logic).
 */
@Component
public class RestCoreApiRunSetClient implements CoreApiRunSetClient {

    private static final String BASE = "/internal/apitest/run-sets";

    private final RestClient rest;

    public RestCoreApiRunSetClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public RunSetView createRunSet(RunSetCreateRequest request) {
        return call(HttpMethod.POST, BASE, request,
                new ParameterizedTypeReference<Response<RunSetView>>() {
                });
    }

    @Override
    public RunSetDetailView getRunSetDetail(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<RunSetDetailView>>() {
                });
    }

    @Override
    public List<RunSetPreviewStepView> previewRunSet(long collectionId) {
        return call(HttpMethod.GET, BASE + "/previews?collectionId=" + collectionId, null,
                new ParameterizedTypeReference<Response<List<RunSetPreviewStepView>>>() {
                });
    }

    @Override
    public RunSetPageView listRunSetsByCollection(long collectionId, int page, int pageSize) {
        return call(HttpMethod.GET, BASE + "?collectionId=" + collectionId
                        + "&page=" + page + "&pageSize=" + pageSize, null,
                new ParameterizedTypeReference<Response<RunSetPageView>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
