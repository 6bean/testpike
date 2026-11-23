package com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class RestLlmConfigClient implements LlmConfigClient {

    private final RestClient rest;

    public RestLlmConfigClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<LlmConfigView> list(Integer modelType) {
        String uri = modelType == null ? "/internal/llm-configs"
                : "/internal/llm-configs?modelType=" + modelType;
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public LlmConfigView get(long id) {
        return call(HttpMethod.GET, "/internal/llm-configs/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public LlmConfigView create(LlmConfigSaveRequest request) {
        return call(HttpMethod.POST, "/internal/llm-configs", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public LlmConfigView update(long id, LlmConfigSaveRequest request) {
        return call(HttpMethod.PUT, "/internal/llm-configs/" + id, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/llm-configs/" + id, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ProjectLlmConfigRef getProjectConfig(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/llm-config", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ProjectLlmConfigRef updateProjectConfig(long projectId, Long llmConfigId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llmConfigId", llmConfigId);
        return call(HttpMethod.PUT, "/internal/projects/" + projectId + "/llm-config", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<LlmConfigView> listEnabled() {
        return list().stream().filter(c -> c.status() == 1 && c.modelType() == 1).toList();
    }

    @Override
    public GoldenDriftPageView routeReplays(String domain, String controlRef, String candidateRef,
                                            int page, int pageSize) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath("/internal/behavior/golden-drift/route-verdicts")
                .queryParam("page", page)
                .queryParam("pageSize", pageSize);
        if (domain != null && !domain.isBlank()) {
            builder.queryParam("domain", domain);
        }
        if (controlRef != null && !controlRef.isBlank()) {
            builder.queryParam("controlRef", controlRef);
        }
        if (candidateRef != null && !candidateRef.isBlank()) {
            builder.queryParam("candidateRef", candidateRef);
        }
        return call(HttpMethod.GET, builder.encode().toUriString(), null,
                new ParameterizedTypeReference<>() {
                });
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
