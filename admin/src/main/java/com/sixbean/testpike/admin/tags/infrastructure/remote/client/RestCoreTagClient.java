package com.sixbean.testpike.admin.tags.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.tags.api.dto.TagStatsView;
import com.sixbean.testpike.admin.tags.api.dto.TagView;
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
public class RestCoreTagClient implements CoreTagClient {

    private final RestClient rest;

    public RestCoreTagClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public TagView createTag(long projectId, String name, Integer type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("name", name);
        body.put("type", type);
        return call(HttpMethod.POST, "/internal/tags", body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public TagView renameTag(long tagId, String newName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newName", newName);
        return call(HttpMethod.PUT, "/internal/tags/" + tagId, body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void mergeTags(long fromTagId, long toTagId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromTagId", fromTagId);
        body.put("toTagId", toTagId);
        call(HttpMethod.POST, "/internal/tags/merge", body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void deleteTag(long tagId) {
        call(HttpMethod.DELETE, "/internal/tags/" + tagId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<TagView> listTags(long projectId, String sortBy, String sortDir) {
        // Encoded pass-through: off-whitelist / injection-shaped keys reach core
        // as data (400 fail-closed there), never as a URI structure element.
        String uri = UriComponentsBuilder.fromPath("/internal/tags")
                .queryParam("projectId", projectId)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy).filter(s -> !s.isBlank()))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<TagView> listVisibleTags(List<Long> projectIds) {
        return call(HttpMethod.GET, "/internal/tags/visible?projectIds=" + joinIds(projectIds),
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public TagView getTag(long tagId) {
        return call(HttpMethod.GET, "/internal/tags/" + tagId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<TagStatsView> tagStats(long projectId) {
        return call(HttpMethod.GET, "/internal/tags/stats?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<TagStatsView> tagStatsVisible(List<Long> projectIds) {
        return call(HttpMethod.GET, "/internal/tags/stats/visible?projectIds=" + joinIds(projectIds),
                null, new ParameterizedTypeReference<>() {
                });
    }

    
    private String joinIds(List<Long> projectIds) {
        return projectIds == null ? "" : String.join(",", projectIds.stream()
                .filter(java.util.Objects::nonNull).map(String::valueOf).toList());
    }

    @Override
    public Map<Long, List<TagView>> tagsByCases(long projectId, List<Long> caseIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseIds", caseIds == null ? List.of() : caseIds);
        return call(HttpMethod.POST, "/internal/tags/by-cases", body, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<TagView> setCaseTags(long caseId, long projectId, List<Long> tagIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("tagIds", tagIds == null ? List.of() : tagIds);
        return call(HttpMethod.PUT, "/internal/tags/cases/" + caseId + "/tags", body,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<TagView> caseTags(long caseId) {
        return call(HttpMethod.GET, "/internal/tags/cases/" + caseId + "/tags", null,
                new ParameterizedTypeReference<>() {
                });
    }

    
    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
