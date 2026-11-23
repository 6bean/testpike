package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScaleEventView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScalePlanView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolWatermarkView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * RestClient implementation of the fleet-plan read port (RestCoreClusterComponentClient
 * precedent: GET body=null, operator id passed through, shared call logic stays in
 * RestClientInvoker — no second HTTP helper). ExecutorPoolScalePlanView mirrors the core
 * record field by field, so the envelope binds without custom mapping.
 */
@Component
public class RestCoreExecutorPoolScaleClient implements CoreExecutorPoolScaleClient {

    private final RestClient rest;

    public RestCoreExecutorPoolScaleClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<ExecutorPoolScalePlanView> scalePlan(double budgetUtilization) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/cluster/pools/scale-plan?budgetUtilization=" + budgetUtilization, null,
                new ParameterizedTypeReference<Response<List<ExecutorPoolScalePlanView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ExecutorPoolScaleEventView> scaleEvents(String poolKey, Integer limit) {
        // Optional filters are omitted, not guessed: absent poolKey spans the fleet and
        // absent limit lets the core ledger apply its own default window (single clamp).
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/cluster/pools/scale-events");
        if (poolKey != null && !poolKey.isBlank()) {
            builder.queryParam("poolKey", poolKey.strip());
        }
        if (limit != null) {
            builder.queryParam("limit", limit);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<List<ExecutorPoolScaleEventView>>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public List<ExecutorPoolWatermarkView> watermarks(String executorType, Integer limit) {
        // Same omission posture as scaleEvents: absent executorType spans the fleet and
        // absent limit lets the core ledger apply its own default window (single clamp).
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/cluster/pools/watermarks");
        if (executorType != null && !executorType.isBlank()) {
            builder.queryParam("executorType", executorType.strip());
        }
        if (limit != null) {
            builder.queryParam("limit", limit);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<List<ExecutorPoolWatermarkView>>>() {
                }, UserContext.currentUserId());
    }
}
