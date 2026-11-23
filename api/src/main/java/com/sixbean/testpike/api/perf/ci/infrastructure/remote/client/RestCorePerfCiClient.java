package com.sixbean.testpike.api.perf.ci.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * RestClient implementation of the core perf CI bridge (mirrors
 * RestCoreCiBatchClient posture: one shared invader, X-Operator-Id forwarded
 * where core reads the login context for audit attribution).
 * <p>The launch door hits the same internal endpoint the web console uses —
 * CI and web share one launch chain, so the A-1 safety gates refuse a bad
 * pipeline request exactly as they refuse a bad console click.
 */
@Component
public class RestCorePerfCiClient implements PerfCiClient {

    private static final ParameterizedTypeReference<Response<Long>> RESP_LONG =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<Response<Map<String, Object>>> RESP_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCorePerfCiClient(
            @Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public long launch(long projectId, long scenarioId, Long envId, long operatorId) {
        // Zero body: every gate, precheck and snapshot decision lives in the core
        // launch transaction; envId absent = scenario-bound default (GB-1 semantics).
        String uri = "/internal/perf/scenarios/" + scenarioId + "/runs?projectId=" + projectId
                + (envId == null ? "" : "&envId=" + envId);
        Long runId = RestClientInvoker.call(rest, HttpMethod.POST, uri, null, RESP_LONG, operatorId);
        return runId == null ? 0L : runId;
    }

    @Override
    public Map<String, Object> view(long runId, long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/perf/runs/" + runId + "?projectId=" + projectId,
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> ciVerdict(long runId, long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/perf/runs/" + runId + "/ci-verdict?projectId=" + projectId,
                null, RESP_MAP, null);
    }
}
