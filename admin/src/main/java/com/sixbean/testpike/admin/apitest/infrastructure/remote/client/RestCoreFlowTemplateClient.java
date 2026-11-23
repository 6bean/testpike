package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplatePageDto;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateView;
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
import java.util.Map;

/**
 * RestClient implementation of the flow-template client .
 * X-Operator-Id passthrough for audit closure; 400/404 semantics preserved by
 * RestClientInvoker.call (shared single point — no duplicated call logic).
 */
@Component
public class RestCoreFlowTemplateClient implements CoreFlowTemplateClient {

    private static final String BASE = "/internal/apitest/flow-templates";

    private final RestClient rest;

    public RestCoreFlowTemplateClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public FlowTemplateView saveTemplate(FlowTemplateSaveRequest request) {
        return call(HttpMethod.POST, BASE, request,
                new ParameterizedTypeReference<Response<FlowTemplateView>>() {
                });
    }

    @Override
    public FlowTemplateView getTemplate(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<FlowTemplateView>>() {
                });
    }

    @Override
    public FlowTemplatePageDto listTemplates(long projectId, int page, int pageSize) {
        return call(HttpMethod.GET, BASE + "?projectId=" + projectId
                        + "&page=" + page + "&pageSize=" + pageSize, null,
                new ParameterizedTypeReference<Response<FlowTemplatePageDto>>() {
                });
    }

    @Override
    public List<RequestView> instantiateTemplate(long id, long targetCollectionId) {
        return call(HttpMethod.POST, BASE + "/" + id + "/instantiate",
                Map.of("targetCollectionId", targetCollectionId),
                new ParameterizedTypeReference<Response<List<RequestView>>>() {
                });
    }

    @Override
    public void deleteTemplate(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
