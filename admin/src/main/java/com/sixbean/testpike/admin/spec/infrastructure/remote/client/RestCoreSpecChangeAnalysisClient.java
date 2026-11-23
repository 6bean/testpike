package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecChangeAnalysisView;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeHandleRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecChangeReanalyzeRequest;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RestClient implementation of the core change-analysis client (same base path as
 * RestCoreDocChangeAnalysisClient — call logic reuses RestClientInvoker, the operator
 * travels through X-Operator-Id from UserContext, never from HttpServletRequest).
 * core business errors (400/404) pass through with their plain-language messages intact.
 */
@Component
public class RestCoreSpecChangeAnalysisClient implements CoreSpecChangeAnalysisClient {

    private final RestClient rest;

    public RestCoreSpecChangeAnalysisClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ApiSpecChangeAnalysisView reanalyze(long newSpecId, String detectedSource) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/api-spec-change-analyses/reanalyze",
                new SpecChangeReanalyzeRequest(newSpecId, detectedSource == null ? "poll" : detectedSource),
                new ParameterizedTypeReference<Response<ApiSpecChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<ApiSpecChangeAnalysisView> list(long projectId, Integer status,
                                                    int page, int size) {
        String uri = "/internal/api-spec-change-analyses?projectId=" + projectId
                + (status == null ? "" : "&status=" + status)
                + "&page=" + page + "&size=" + size;
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<PageView<ApiSpecChangeAnalysisView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ApiSpecChangeAnalysisView get(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/api-spec-change-analyses/" + id, null,
                new ParameterizedTypeReference<Response<ApiSpecChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ApiSpecChangeAnalysisView getBySpec(long newSpecId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/api-spec-change-analyses/by-spec/" + newSpecId, null,
                new ParameterizedTypeReference<Response<ApiSpecChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public ApiSpecChangeAnalysisView handle(long id, String note) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/api-spec-change-analyses/" + id + "/handle",
                new SpecChangeHandleRequest(null, note),
                new ParameterizedTypeReference<Response<ApiSpecChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ApiSpecChangeAnalysisView> handleBatch(List<Long> ids, String note) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/api-spec-change-analyses/handle-batch",
                new SpecChangeHandleRequest(ids, note),
                new ParameterizedTypeReference<Response<List<ApiSpecChangeAnalysisView>>>() {
                }, UserContext.currentUserId());
    }
}
