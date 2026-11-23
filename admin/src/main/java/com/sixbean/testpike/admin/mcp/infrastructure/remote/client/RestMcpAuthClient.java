package com.sixbean.testpike.admin.mcp.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpAuthTemplateView;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthRequest;
import com.sixbean.testpike.admin.mcp.api.dto.ProjectMcpAuthView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;


@Component
public class RestMcpAuthClient implements McpAuthClient {

    private final RestClient rest;

    public RestMcpAuthClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<McpAuthTemplateView> listTemplates() {
        return call(HttpMethod.GET, "/internal/mcp-auth/templates", null,
                new ParameterizedTypeReference<Response<List<McpAuthTemplateView>>>() {
                });
    }

    @Override
    public McpAuthTemplateView create(McpAuthTemplateRequest request) {
        return call(HttpMethod.POST, "/internal/mcp-auth/templates", request,
                new ParameterizedTypeReference<Response<McpAuthTemplateView>>() {
                });
    }

    @Override
    public McpAuthTemplateView update(long id, McpAuthTemplateRequest request) {
        return call(HttpMethod.PUT, "/internal/mcp-auth/templates/" + id, request,
                new ParameterizedTypeReference<Response<McpAuthTemplateView>>() {
                });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/mcp-auth/templates/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public McpAuthApplyResult apply(long id, McpAuthApplyRequest request) {
        return call(HttpMethod.POST, "/internal/mcp-auth/templates/" + id + "/apply", request,
                new ParameterizedTypeReference<Response<McpAuthApplyResult>>() {
                });
    }

    @Override
    public ProjectMcpAuthView getProjectAuth(long projectId) {
        return call(HttpMethod.GET, "/internal/mcp-auth/projects/" + projectId, null,
                new ParameterizedTypeReference<Response<ProjectMcpAuthView>>() {
                });
    }

    @Override
    public ProjectMcpAuthView putProjectAuth(long projectId, ProjectMcpAuthRequest request) {
        return call(HttpMethod.PUT, "/internal/mcp-auth/projects/" + projectId, request,
                new ParameterizedTypeReference<Response<ProjectMcpAuthView>>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
