package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncRoundView;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncUpsertRequest;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the knowledge sync ledger client (same recipe
 * as RestMcpServerClient: RestClientInvoker carries X-Operator-Id so the core
 * audit chain closes).
 * <p>The optional project filter is appended only when present: an absent
 * value never travels as a literal "null" query parameter, mirroring the
 * core-side rule that optional predicates bind only real values.
 */
@Component
public class RestMcpKnowledgeSyncClient implements McpKnowledgeSyncClient {

    private static final String BASE = "/internal/mcp-knowledge-syncs";

    private final RestClient rest;

    public RestMcpKnowledgeSyncClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<KnowledgeSyncView> list(long serverId, Long projectId, int page, int size) {
        StringBuilder uri = new StringBuilder(BASE).append("?serverId=").append(serverId)
                .append("&page=").append(page).append("&size=").append(size);
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public KnowledgeSyncView detail(long id) {
        return call(HttpMethod.GET, BASE + "/" + id, null, viewType());
    }

    @Override
    public KnowledgeSyncView save(KnowledgeSyncUpsertRequest request) {
        return call(HttpMethod.POST, BASE, request, viewType());
    }

    @Override
    public KnowledgeSyncView update(long id, KnowledgeSyncUpsertRequest request) {
        return call(HttpMethod.PUT, BASE + "/" + id, request, viewType());
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, BASE + "/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void pause(long id) {
        call(HttpMethod.POST, BASE + "/" + id + "/pause", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public void resume(long id) {
        call(HttpMethod.POST, BASE + "/" + id + "/resume", null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public KnowledgeSyncRoundView runNow(long id) {
        return call(HttpMethod.POST, BASE + "/" + id + "/run-now", null, roundType());
    }

    private ParameterizedTypeReference<Response<KnowledgeSyncView>> viewType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private ParameterizedTypeReference<Response<KnowledgeSyncRoundView>> roundType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
