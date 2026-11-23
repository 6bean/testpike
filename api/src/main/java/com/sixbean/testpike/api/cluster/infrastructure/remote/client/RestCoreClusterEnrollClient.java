package com.sixbean.testpike.api.cluster.infrastructure.remote.client;

import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.genkit.GenKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class RestCoreClusterEnrollClient implements CoreClusterEnrollClient {

    private static final ParameterizedTypeReference<Response<Map<String, Object>>> RESP_MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Response<List<Map<String, Object>>>> RESP_LIST =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Response<Void>> RESP_VOID =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient rest;

    public RestCoreClusterEnrollClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public Map<String, Object> enroll(String token, String fingerprint) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/enroll",
                Map.of("token", token == null ? "" : token,
                        "fingerprint", fingerprint == null ? "" : fingerprint),
                RESP_MAP, null);
    }

    @Override
    public Map<String, Object> heartbeat(String token, String fingerprint, String step,
                                         String stepStatus, String message, List<?> components) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token == null ? "" : token);
        body.put("fingerprint", fingerprint == null ? "" : fingerprint);
        if (step != null && !step.isBlank()) {
            body.put("step", step);
            if (stepStatus != null && !stepStatus.isBlank()) {
                body.put("stepStatus", stepStatus);
            }
            if (message != null && !message.isBlank()) {
                body.put("message", message);
            }
        }
        // package C (03 §2.8): trailing components ledger — rows forwarded
        // as-is (List of Map, zero re-shaping; core owns validation and ingest).
        // Empty/null = key omitted = old reporters zero drift.
        if (components != null && !components.isEmpty()) {
            body.put("components", components);
        }
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/enroll/heartbeat",
                body, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> installkitConfig() {
        return RestClientInvoker.call(rest, HttpMethod.GET, "/internal/cluster/installkit-config",
                null, RESP_MAP, null);
    }

    @Override
    public Map<String, Object> engineRecipe(String code) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/appauto/engines/" + code, null, RESP_MAP, null);
    }

    @Override
    public List<Map<String, Object>> pollCommand(Map<String, Object> body) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/commands/poll",
                foldTokenDigest(body), RESP_LIST, null);
    }

    @Override
    public void progressCommand(Map<String, Object> body) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/commands/progress",
                foldTokenDigest(body), RESP_VOID, null);
    }

    @Override
    public void completeCommand(Map<String, Object> body) {
        RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cluster/commands/result",
                foldTokenDigest(body), RESP_VOID, null);
    }

    
    private static Map<String, Object> foldTokenDigest(Map<String, Object> body) {
        Object token = body.get("token");
        if (token == null) {
            return body;
        }
        Map<String, Object> folded = new HashMap<>(body);
        folded.remove("token");
        String plaintext = String.valueOf(token).trim();
        if (!plaintext.isEmpty()) {
            folded.put("tokenDigest", GenKit.sha256(plaintext));
        }
        return folded;
    }
}
