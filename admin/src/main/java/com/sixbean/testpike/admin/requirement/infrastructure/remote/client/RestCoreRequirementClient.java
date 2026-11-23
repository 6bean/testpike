package com.sixbean.testpike.admin.requirement.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.requirement.api.dto.CaseBriefView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementVersionView;
import com.sixbean.testpike.admin.requirement.api.dto.CreateRequirementRequest;
import com.sixbean.testpike.admin.requirement.api.dto.LinkResultView;
import com.sixbean.testpike.admin.requirement.api.dto.RequirementView;
import com.sixbean.testpike.admin.requirement.api.dto.TraceChainView;
import com.sixbean.testpike.admin.requirement.api.dto.UpdateRequirementRequest;
import com.sixbean.testpike.common.client.RestClientInvoker;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Component
public class RestCoreRequirementClient implements CoreRequirementClient {

    private final RestClient rest;

    public RestCoreRequirementClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                                      int page, int size, String sortBy, String sortDir,
                                                      String ids, String fingerprint) {
        String uri = UriComponentsBuilder.fromPath("/internal/requirements")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .queryParam("page", page)
                .queryParam("size", size)
                // Percent-encode the caller-supplied sort key: an injection-shaped
                // value with raw spaces must reach the whitelist gate as an honest
                // 400, not blow up URI parsing as a 500 L3 finding).
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy).filter(s -> !s.isBlank()))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir).filter(s -> !s.isBlank()))
                // deep-link handoff: comma-separated id allow-list, blank = no filter.
                .queryParamIfPresent("ids", Optional.ofNullable(ids).filter(s -> !s.isBlank()))
                // segment B: exact content fingerprint, blank = no filter.
                .queryParamIfPresent("fingerprint", Optional.ofNullable(fingerprint).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PageView<RequirementView> listRequirements(long projectId, Integer status, String keyword,
                                                      int page, int size, String sortBy, String sortDir) {
        return listRequirements(projectId, status, keyword, page, size, sortBy, sortDir, null);
    }

    @Override
    public PageView<RequirementView> listUncoveredRequirements(long projectId, int page, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/requirements/uncovered")
                .queryParam("projectId", projectId)
                .queryParam("page", page)
                .queryParam("size", size)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public RequirementView getRequirement(long requirementId) {
        return call(HttpMethod.GET, "/internal/requirements/" + requirementId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public RequirementView createRequirement(long projectId, CreateRequirementRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        putIfNotNull(body, "source", request.source());
        putIfNotNull(body, "sourceId", request.sourceId());
        body.put("title", request.title());
        putIfNotNull(body, "acceptanceCriteria", request.acceptanceCriteria());
        putIfNotNull(body, "chapter", request.chapter());
        return call(HttpMethod.POST, "/internal/requirements", body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public RequirementView updateRequirement(long requirementId, UpdateRequirementRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfNotNull(body, "title", request.title());
        putIfNotNull(body, "source", request.source());
        putIfNotNull(body, "sourceId", request.sourceId());
        putIfNotNull(body, "acceptanceCriteria", request.acceptanceCriteria());
        putIfNotNull(body, "chapter", request.chapter());
        putIfNotNull(body, "status", request.status());
        putIfNotNull(body, "expectedVersion", request.expectedVersion());
        return call(HttpMethod.PUT, "/internal/requirements/" + requirementId, body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long deleteRequirement(long requirementId) {
        Long id = call(HttpMethod.DELETE, "/internal/requirements/" + requirementId, null,
                new ParameterizedTypeReference<>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public LinkResultView linkCases(long requirementId, List<Long> caseIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseIds", caseIds);
        return call(HttpMethod.POST, "/internal/requirements/" + requirementId + "/cases", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long unlinkCase(long requirementId, long caseId) {
        Long id = call(HttpMethod.DELETE, "/internal/requirements/" + requirementId + "/cases/" + caseId,
                null, new ParameterizedTypeReference<>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public List<RequirementView> listRequirementsByCaseId(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/requirements", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<CaseBriefView> listCasesByRequirementId(long requirementId) {
        return call(HttpMethod.GET, "/internal/requirements/" + requirementId + "/cases", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<RequirementVersionView> listRequirementVersions(long requirementId) {
        return call(HttpMethod.GET, "/internal/requirements/" + requirementId + "/versions", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<Long> offlineCaseIds(long projectId, List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of("projectId", projectId, "caseIds", caseIds);
        return call(HttpMethod.POST, "/internal/requirements/cases/offline-check", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public TraceChainView traceChainOfRequirement(long requirementId) {
        return call(HttpMethod.GET, "/internal/requirements/" + requirementId + "/trace-chain",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public TraceChainView traceChainOfCase(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/trace-chain",
                null, new ParameterizedTypeReference<>() {
                });
    }

    
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
