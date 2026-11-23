package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.DocChangeAnalysisView;
import com.sixbean.testpike.admin.ai.api.dto.HandleChangeRequest;
import com.sixbean.testpike.admin.ai.api.dto.ReanalyzeRequest;
import com.sixbean.testpike.admin.ai.api.dto.RevisionDraftPregenView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreDocChangeAnalysisClient implements CoreDocChangeAnalysisClient {

    private final RestClient rest;

    public RestCoreDocChangeAnalysisClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public DocChangeAnalysisView analyze(long newDocumentId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/doc-change-analyses/reanalyze", new ReanalyzeRequest(newDocumentId),
                new ParameterizedTypeReference<Response<DocChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<DocChangeAnalysisView> list(long projectId, Integer status, int page, int size) {
        String uri = "/internal/doc-change-analyses?projectId=" + projectId
                + (status == null ? "" : "&status=" + status)
                + "&page=" + page + "&size=" + size;
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<PageView<DocChangeAnalysisView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public DocChangeAnalysisView get(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/doc-change-analyses/" + id, null,
                new ParameterizedTypeReference<Response<DocChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public DocChangeAnalysisView handle(long id, String note) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/doc-change-analyses/" + id + "/handle", new HandleChangeRequest(note),
                new ParameterizedTypeReference<Response<DocChangeAnalysisView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public RevisionDraftPregenView pregenDrafts(long id) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/doc-change-analyses/" + id + "/pregen-drafts", null,
                new ParameterizedTypeReference<Response<RevisionDraftPregenView>>() {
                }, UserContext.currentUserId());
    }
}
