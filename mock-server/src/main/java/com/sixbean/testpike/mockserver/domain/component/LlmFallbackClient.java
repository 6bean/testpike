package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The data plane's second outbound leg : asks core's A-3
 * answer door once per plain miss. The posture mirrors RuleLoader's snapshot
 * poll — same coreMockClient RestClient, tolerant envelope read, and an
 * absolute degradation contract: ANY trouble (transport failure, business
 * refusal envelope, unparsable body, incomplete artifact) decodes to
 * {@link LlmFallbackOutcome#miss} and the traffic plane falls through to its
 * original 404+trace semantics. The mock never becomes a 500 because its
 * fallback helper is unhappy, and the fail-closed rule-store stance of
 * RuleLoader is untouched by this leg (separate call site, no shared state).
 */
public class LlmFallbackClient {

    private static final Logger log = LoggerFactory.getLogger(LlmFallbackClient.class);

    private final RestClient coreClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmFallbackClient(RestClient coreClient) {
        this.coreClient = coreClient;
    }

    /** One lookup: pinned space id + method + normalized path + masked digest + lane. */
    public LlmFallbackOutcome answer(long spaceId, String method, String path,
                                     String maskedFingerprint, String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("spaceId", spaceId);
        payload.put("method", method);
        payload.put("path", path);
        payload.put("maskedFingerprint", maskedFingerprint);
        // segment 7a session-summary leg: the lane rides so core can read what
        // already happened on it (null = no lane, the context stays absent).
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId);
        }
        try {
            String json = coreClient.post()
                    .uri("/internal/mock/llm-fallback/answer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return decode(json);
        } catch (Exception ex) {
            log.warn("llm-fallback answer leg failed: {}", ex.getMessage());
            return LlmFallbackOutcome.miss(
                    "core 兜底答询通道当前不可用（" + ex.getClass().getSimpleName() + "），按未命中语义回落");
        }
    }

    /**
     * Envelope decode, fail-soft by design: code != "0" (gate refusal or any
     * business error) and every malformed shape become honest misses in
     * plain words — never an exception on the traffic thread.
     */
    private LlmFallbackOutcome decode(String json) {
        try {
            JsonNode root = mapper.readTree(json == null ? "{}" : json);
            if (!"0".equals(root.path("code").asText(""))) {
                return LlmFallbackOutcome.miss(root.path("msg").asText("core 拒绝了兜底答询"));
            }
            JsonNode data = root.path("data");
            if (!data.isObject() || !data.path("served").asBoolean(false)) {
                return LlmFallbackOutcome.miss(data.path("missReason")
                        .asText("缓存无新鲜行且代答通道未产出答案"));
            }
            int status = data.path("status").asInt(0);
            String body = data.path("body").isTextual() ? data.path("body").asText() : null;
            if (status < 100 || status > 599 || body == null) {
                // an incomplete artifact is never served (same gate as core's
                // own read side — belt and suspenders on the wire boundary)
                return LlmFallbackOutcome.miss("兜底产物不完整，拒绝充当应答");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            data.path("headers").fields().forEachRemaining(
                    e -> headers.put(e.getKey(), e.getValue().asText()));
            return new LlmFallbackOutcome(true, status, headers, body,
                    data.path("cacheHit").asBoolean(false),
                    data.path("tokensSpent").asInt(0), null);
        } catch (Exception ex) {
            return LlmFallbackOutcome.miss("兜底答询响应无法解析，按未命中语义回落");
        }
    }
}
