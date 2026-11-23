package com.sixbean.testpike.api.executor.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;


@Component
public class RestCoreExecutorClient implements CoreExecutorClient {

    private static final ParameterizedTypeReference<Response<Map<String, Object>>> RESP_MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Response<java.util.List<Map<String, Object>>>>
            RESP_LIST = new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Response<Long>> RESP_LONG =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCoreExecutorClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public Map<String, Object> register(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/executors/register",
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> heartbeat(long executorId, Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/heartbeat?executorId=" + executorId,
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> claim(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/executors/tasks/claim",
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> submitResult(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/executors/tasks/result",
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> grantArtifact(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/executors/tasks/artifact",
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> ingestSessionArtifacts(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/tasks/session-artifacts", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> cancelTask(long taskId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/executors/tasks/" + taskId + "/cancel", null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> occupyPoolItem(long itemId, long projectId, long lockedBy) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/data-factories/pool-items/" + itemId + "/occupy?projectId=" + projectId
                        + "&lockedBy=" + lockedBy,
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> releasePoolItem(long itemId, long projectId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/data-factories/pool-items/" + itemId + "/release?projectId=" + projectId,
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> renewDeviceLease(long deviceId, long executorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/devices/" + deviceId + "/lease/renew?holderExecutorId=" + executorId,
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> releaseDeviceLease(long deviceId, long executorId, String reason) {
        String query = "?holderExecutorId=" + executorId
                + (reason == null || reason.isBlank() ? ""
                        : "&reason=" + java.net.URLEncoder.encode(reason,
                                java.nio.charset.StandardCharsets.UTF_8));
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/devices/" + deviceId + "/lease/release" + query,
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> visualLocate(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/visual-locate", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> aiSampleCacheHit(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/ai-samples/cache-hit", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> aiSampleRecord(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/ai-samples/record", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> stepSuggestionRecord(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/step-suggestions/record", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> elementEvidenceGrant(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/web-elements/evidence-grant", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> grantPackageDownload(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/packages/download-grant", body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> grantPerfArtifactDownload(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/perf/artifacts/download-grant", body, RESP_MAP, null);
    }

    @Override
    public java.util.List<Map<String, Object>> takeoverInputPoll(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/appauto/takeover/input/poll", body, RESP_LIST, null);
    }

    /**
 * Capture-session hand-off relay : the api protocol door only
 * forwards the executor id; the queue pop, the window check and the attempt
 * counter are core's. Nothing here echoes the debug endpoint anywhere but
 * back to the calling executor.
 */
    @Override
    public Map<String, Object> claimCaptureSession(long executorId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/session-capture-sessions/claim?executorId=" + executorId,
                null, RESP_MAP, null);
    }

    @Override
    public Long reportCaptureSession(String sessionId, Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/session-capture-sessions/" + sessionId + "/report",
                body, RESP_LONG, null);
    }

    /**
 * Commit-round value door relay : the body carries freshly read
 * values for the checked subset — this layer forwards it verbatim, stores
 * nothing and logs nothing (red lines 3/4); vault registration and the
 * re-filter of the checked names against the origin whitelist are core's.
 */
    @Override
    public Long commitCaptureValues(String sessionId, Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/session-capture-sessions/" + sessionId + "/commit-values",
                body, RESP_LONG, null);
    }
}
