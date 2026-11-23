package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.admin.ai.api.dto.ScenarioPreviewView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RestClient implementation of the admin → core scenario preview leg
 * ; RestCoreAiBatchClient posture: shared RestClientInvoker so
 * core refusals re-throw in business semantics, X-Operator-Id passthrough for
 * the audit loop). The request object is forwarded whole — never rebuilt field
 * by field (field-loss incident class, JourneyBridgeTest rule 1).
 */
@Component
public class RestCoreAiScenarioPreviewClient implements CoreAiScenarioPreviewClient {

    private final RestClient rest;

    public RestCoreAiScenarioPreviewClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl,
            @Value("${ai.scenario-preview.bridge-read-timeout-seconds:880}") long bridgeReadTimeoutSeconds) {
        // One-row stage0 enumeration is a synchronous bridge leg with a real LLM call
        // downstream (ai-service LLM single-call budget 800s, inner core→ai-service leg
        // 820s under ai.scenario-preview.forward-read-timeout-seconds). This outer leg
        // used to read that same key, so both legs expired in the same instant and the
        // two HttpTimeoutException signatures surfaced as a permanent pair (R43
        // drill-down). It now carries its own key, inner 820 + 60s margin, so a legal
        // long row either lands inside this window or fails alone with the inner cause.
        // Above the R56 per-hop floor of 800s; install-tunable, takes effect on restart.
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl,
                java.time.Duration.ofSeconds(bridgeReadTimeoutSeconds));
    }

    @Override
    public long create(ScenarioPreviewRequest request, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-scenario-previews",
                request, new ParameterizedTypeReference<Response<Long>>() {
                }, operatorId);
    }

    @Override
    public List<ScenarioPreviewView> listByBatch(long batchId, long operatorId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-scenario-previews?batchId=" + batchId, null,
                new ParameterizedTypeReference<Response<List<ScenarioPreviewView>>>() {
                }, operatorId);
    }
}
