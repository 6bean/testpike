package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.McpProbeResultView;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerCreateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerUpdateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerView;
import com.sixbean.testpike.admin.mcp.api.dto.McpToolSelectionRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


@Component
public class RestMcpServerClient implements McpServerClient {

    private final RestClient rest;

    public RestMcpServerClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<McpServerView> list(String keyword, Integer enabled, Integer health,
                                        String sortBy, String sortDir, int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/mcp-servers?page=").append(page)
                .append("&size=").append(size);
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (enabled != null) {
            uri.append("&enabled=").append(enabled);
        }
        if (health != null) {
            uri.append("&health=").append(health);
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(sortDir);
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public McpServerView create(McpServerCreateRequest request) {
        return call(HttpMethod.POST, "/internal/mcp-servers", request, serverType());
    }

    @Override
    public McpServerView get(long id) {
        return call(HttpMethod.GET, "/internal/mcp-servers/" + id, null, serverType());
    }

    @Override
    public McpServerView update(long id, McpServerUpdateRequest request) {
        return call(HttpMethod.PUT, "/internal/mcp-servers/" + id, request, serverType());
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/mcp-servers/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public McpServerView toggle(long id, int enabled) {
        return call(HttpMethod.PUT, "/internal/mcp-servers/" + id + "/toggle?enabled=" + enabled,
                null, serverType());
    }

    @Override
    public McpServerView updateTools(long id, McpToolSelectionRequest request) {
        return call(HttpMethod.PUT, "/internal/mcp-servers/" + id + "/tools", request, serverType());
    }

    @Override
    public McpProbeResultView test(long id) {
        return call(HttpMethod.POST, "/internal/mcp-servers/" + id + "/test", null,
                new ParameterizedTypeReference<>() {
                });
    }

    private ParameterizedTypeReference<Response<McpServerView>> serverType() {
        return new ParameterizedTypeReference<>() {
        };
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
