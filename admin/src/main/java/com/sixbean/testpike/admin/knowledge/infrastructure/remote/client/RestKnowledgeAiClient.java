package com.sixbean.testpike.admin.knowledge.infrastructure.remote.client;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeRebuildResultView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeSearchHit;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeSearchParams;
import com.sixbean.testpike.common.client.RestClientInvoker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;


@Component
public class RestKnowledgeAiClient implements KnowledgeAiClient {

    
    static final Duration REBUILD_READ_TIMEOUT = Duration.ofMinutes(10);

    private final RestClient rest;

    public RestKnowledgeAiClient(
            @Value("${aiservice.base-url:http://localhost:18084}") String aiBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(aiBaseUrl, REBUILD_READ_TIMEOUT);
    }

    @Override
    public KnowledgeRebuildResultView rebuild() {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/knowledge/rebuild",
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<KnowledgeSearchHit> search(Long projectId, String query, String scene, Integer topK) {
        List<KnowledgeSearchHit> hits = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/knowledge/search",
                new KnowledgeSearchParams(projectId, query, scene, topK),
                new ParameterizedTypeReference<>() {
                }, null);
        return hits == null ? List.of() : hits;
    }
}
