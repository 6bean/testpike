package com.sixbean.testpike.admin.skill.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * RestClient implementation of the project-memory ledger client. The operator
 * travels through RestClientInvoker (X-Operator-Id, audit chain closed) and
 * every call reuses that shared invoker — no second calling logic. Generic
 * PageView/Response types are passed as explicit typeRefs (RestSkillClient
 * precedent).
 * <p>keyword is encoded exactly once (URI.create semantics — double-encoding
 * of CJK keywords is a known RestClientInvoker pitfall).
 */
@Component
public class RestProjectMemoryClient implements ProjectMemoryClient {

    private final RestClient rest;

    public RestProjectMemoryClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ProjectMemoryView> list(Long projectId, String keyword, Integer injectStatus,
                                            int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/project-memory?projectId=").append(projectId)
                .append("&page=").append(page).append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (injectStatus != null) {
            uri.append("&injectStatus=").append(injectStatus);
        }
        return call(HttpMethod.GET, uri.toString(), null, pageType());
    }

    @Override
    public ProjectMemoryView create(ProjectMemoryCreateRequest request) {
        return call(HttpMethod.POST, "/internal/project-memory", request, viewType());
    }

    @Override
    public ProjectMemoryView setInjectStatus(long id, Long projectId, int injectStatus) {
        return call(HttpMethod.PUT, "/internal/project-memory/" + id + "/status?projectId="
                + projectId + "&status=" + injectStatus, null, viewType());
    }

    @Override
    public ProjectMemoryView reject(long id, Long projectId) {
        return call(HttpMethod.POST, "/internal/project-memory/" + id + "/reject?projectId="
                + projectId, null, viewType());
    }

    @Override
    public void delete(long id, Long projectId) {
        call(HttpMethod.DELETE, "/internal/project-memory/" + id + "?projectId=" + projectId,
                null, new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public PageView<ProjectMemoryView> recycleBin(Long projectId, int page, int size) {
        return call(HttpMethod.GET, "/internal/project-memory/recycle-bin?projectId=" + projectId
                + "&page=" + page + "&size=" + size, null, pageType());
    }

    @Override
    public ProjectMemoryView restore(long id, Long projectId) {
        return call(HttpMethod.POST, "/internal/project-memory/" + id + "/restore?projectId="
                + projectId, null, viewType());
    }

    private ParameterizedTypeReference<Response<ProjectMemoryView>> viewType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private ParameterizedTypeReference<Response<PageView<ProjectMemoryView>>> pageType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
