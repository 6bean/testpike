package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.AiBatchEstimateView;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchView;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class RestCoreAiBatchClient implements CoreAiBatchClient {

    private final RestClient rest;

    public RestCoreAiBatchClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public AiBatchEstimateView estimateBatch(long projectId, List<AiBatchItem> items, Long previewBatchId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("items", items);
        // null rides as an absent-equivalent value (core treats a
        // missing preview batch exactly like null — the multiplier is never fabricated).
        body.put("previewBatchId", previewBatchId);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-batches/estimate", body,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public Map<String, Integer> batchLimit() {
        Map<String, Integer> body = RestClientInvoker.call(rest, HttpMethod.GET, "/internal/ai-batches/limit", null,
                new ParameterizedTypeReference<>() {
                }, null);
        // Normalize both halves: the shipped defaults keep the page honest when
        // an older core answers only one key (or none).
        int maxCount = body == null || body.get("maxCount") == null ? 500 : body.get("maxCount");
        int autoEnumerate = body == null || body.get("autoEnumerate") == null ? 1 : body.get("autoEnumerate");
        return Map.of("maxCount", maxCount, "autoEnumerate", autoEnumerate);
    }

    @Override
    public Map<String, Long> createBatch(long projectId, List<AiBatchItem> items, long operatorId,
                                         boolean windowFeed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("items", items);
        // Window-feed declaration rides the body (internal transport); the public
        // door is the admin X-Window-Feed header. Core re-checks switch + whitelist.
        body.put("windowFeedDeclared", windowFeed);
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-batches", body,
                new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public AiBatchView getBatch(long batchId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/ai-batches/" + batchId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public AiBatchView latestBatch(long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-batches/latest?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
