package com.sixbean.testpike.admin.knowledge.infrastructure.remote.client;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocDetailView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeHitView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;


@Component
public class RestKnowledgeCoreClient implements KnowledgeCoreClient {

    private final RestClient rest;

    public RestKnowledgeCoreClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<KnowledgeDocView> listDocs(Long projectId, String sourceType, String status,
                                               int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/knowledge/docs?page=" + page
                + "&size=" + size);
        if (projectId != null) {
            uri.append("&projectId=").append(projectId);
        }
        if (sourceType != null) {
            uri.append("&sourceType=").append(enc(sourceType));
        }
        if (status != null) {
            uri.append("&status=").append(enc(status));
        }
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(enc(sortBy));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(enc(sortDir));
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, uri.toString(), null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public KnowledgeDocDetailView docDetail(long id) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/knowledge/docs/" + id, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<KnowledgeHitView> listDocHits(long id, int limit) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/knowledge/docs/" + id + "/hits?limit=" + limit, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    
    private static String enc(String value) {
        return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
    }
}
