package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.core.mock.domain.component.MockRuntimeRefusals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackAnswer;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateInput;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateReply;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreResult;
import com.sixbean.testpike.core.mock.domain.component.MockFallbackContextSupply;
import com.sixbean.testpike.core.mock.domain.service.MockLlmFallbackService;
import com.sixbean.testpike.core.mock.infrastructure.remote.client.AiServiceMockLlmFallbackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A-3 fallback cache read side ; real dev database, the
 * NamedParameterJdbcTemplate path every mock service rides).
 * <p>Ordering inside {@code answer}: dual gate → per-response token budget →
 * cache lookup. The budget gate (K8 {@code mock.llm-fallback.max-tokens-per-response})
 * stands before anything spends, mirroring the drafting-job posture where
 * readers consume pinned values, never live recomputes — but a zero budget
 * degrades to an honest MISS, not an error: the miss path of the traffic
 * plane must keep answering 404+trace, never a 500 dressed up as a fallback.
 * <p>Cache rows are generated artifacts: only a row whose {@code expires_at}
 * still lies in the future may serve (NULL = never expires by age, the V241
 * column semantics). A served hit bumps {@code hit_count} and stamps
 * {@code last_hit_at} in the same statement — the ledger of the cache itself,
 * so operators can tell instant-reused answers from freshly paid ones.
 * <p>segment 2b (generation escalation + cache write) extends this class's
 * contract through the same interface; nothing here pretends it exists yet:
 * a cold lookup answers miss with the reason the caller will surface.
 */
@Service
public class MockLlmFallbackServiceImpl implements MockLlmFallbackService {

    private static final Logger log = LoggerFactory.getLogger(MockLlmFallbackServiceImpl.class);

    /** K8 hot key: master gate for every mock AI application point. */
    static final String MASTER_KEY = "ai.mock.enabled";
    /** K8 hot key: A-3 per-point sub-switch, ANDed with the master. */
    static final String SUB_KEY = "ai.mock.llm-fallback";
    /** K8 hot key: token ceiling one fallback response may cost. */
    static final String BUDGET_KEY = "mock.llm-fallback.max-tokens-per-response";
    /** K8 hot key: cache freshness horizon in hours (<=0 = never expires by age). */
    static final String TTL_KEY = "mock.llm-fallback.cache-ttl-hours";
    static final int DEFAULT_TTL_HOURS = 72;

    private final NamedParameterJdbcTemplate jdbc;
    private final SystemConfigReader configReader;
    private final ObjectMapper objectMapper;
    private final AiServiceMockLlmFallbackClient fallbackClient;
    private final MockFallbackContextSupply contextSupply;

    public MockLlmFallbackServiceImpl(NamedParameterJdbcTemplate jdbc,
                                      SystemConfigReader configReader,
                                      ObjectMapper objectMapper,
                                      AiServiceMockLlmFallbackClient fallbackClient,
                                      MockFallbackContextSupply contextSupply) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
        this.fallbackClient = fallbackClient;
        this.contextSupply = contextSupply;
    }

    @Override
    public MockLlmFallbackAnswer answer(MockLlmFallbackRequest request) {
        assertGatesOpen();
        String cacheKey = MockLlmFallbackService.cacheKeyFor(
                request.method(), request.path(), request.maskedFingerprint());
        int budget = configReader.getInt(BUDGET_KEY, 2_000);
        if (budget <= 0) {
            // over-budget falls to miss in plain words — no token is burned
            // and the traffic plane's 404+trace path stays untouched
            return MockLlmFallbackAnswer.miss(cacheKey,
                    "AI 兜底单次 token 预算为 0（配置项 " + BUDGET_KEY
                            + "），未命中请求按原语义回落，不调模型");
        }
        CacheRow row = findFresh(cacheKey, request.spaceId());
        if (row == null) {
            // segment 2c: a cold miss escalates to the sync generation leg
            // exactly once. Any trouble on that leg — an unreachable
            // provider, an incomplete artifact, a refused store — degrades
            // back to this same honest miss; the traffic plane's 404+trace
            // semantics never become a 500 dressed up as a fallback answer.
            return escalateAndAnswer(request, cacheKey);
        }
        JsonNode snapshot = parseSnapshot(row.snapshotJson());
        if (snapshot == null) {
            // a corrupt artifact is never served — degrade to the honest miss
            return MockLlmFallbackAnswer.miss(cacheKey,
                    "缓存行的响应快照不可解析，拒绝充当兜底应答（数据面按未命中语义回落）");
        }
        recordHit(row.id());
        return new MockLlmFallbackAnswer(true, true, snapshot.path("status").asInt(200),
                readHeaders(snapshot), readBody(snapshot), 0, null, cacheKey);
    }

    @Override
    public MockLlmFallbackStoreResult store(MockLlmFallbackStoreRequest request) {
        assertGatesOpen();
        validatePayload(request);
        int budget = configReader.getInt(BUDGET_KEY, 2_000);
        String cacheKey = MockLlmFallbackService.cacheKeyFor(
                request.method(), request.path(), request.maskedFingerprint());
        if (request.tokensSpent() > budget) {
            // the ceiling guards both directions: an oversized generation is
            // refused in words, never cased as a pre-paid future answer
            return MockLlmFallbackStoreResult.refused(cacheKey,
                    "本次生成成本 " + request.tokensSpent() + " tokens 超过单次预算 "
                            + budget + "（配置项 " + BUDGET_KEY + "），不入库");
        }
        LocalDateTime expiresAt = expiresHorizon();
        // one fingerprint, one row: a newer answer replaces the stale artifact
        // and resets its hit ledger — statistics of the replaced body would
        // otherwise misattribute reuse to an answer nobody ever served
        jdbc.update("""
                INSERT INTO tp_mock_llm_cache (space_id, cache_key, method, path,
                    response_snapshot, tokens_spent, hit_count, last_hit_at, expires_at)
                VALUES (:spaceId, :cacheKey, :method, :path,
                    CAST(:snapshot AS jsonb), :tokens, 0, NULL, :expiresAt)
                ON CONFLICT (space_id, cache_key) DO UPDATE SET
                    method = EXCLUDED.method,
                    path = EXCLUDED.path,
                    response_snapshot = EXCLUDED.response_snapshot,
                    tokens_spent = EXCLUDED.tokens_spent,
                    hit_count = 0,
                    last_hit_at = NULL,
                    expires_at = EXCLUDED.expires_at
                """, new MapSqlParameterSource()
                .addValue("spaceId", request.spaceId())
                .addValue("cacheKey", cacheKey)
                .addValue("method", normalizeMethod(request.method()))
                .addValue("path", request.path())
                .addValue("snapshot", snapshotJson(request))
                .addValue("tokens", request.tokensSpent())
                .addValue("expiresAt", expiresAt));
        return new MockLlmFallbackStoreResult(true, cacheKey, null, expiresAt);
    }

    /**
 * One cold-miss escalation to the ai-service sync generation door .
 * <p>Honest degradation ladder, each rung an unserved answer in plain
 * words: no owning project to route the model call → provider leg threw
 * (down/timeout/business refusal) → artifact incomplete (null body or a
 * status outside 100..599 can never serve honestly later). A paid answer
 * stands even when the store door refused it — the bookkeeping verdict
 * never claws back tokens already spent for THIS caller.
 */
    private MockLlmFallbackAnswer escalateAndAnswer(MockLlmFallbackRequest request,
                                                    String cacheKey) {
        Long projectId = findSpaceProjectId(request.spaceId());
        if (projectId == null) {
            return MockLlmFallbackAnswer.miss(cacheKey,
                    "兜底空间行不存在或无归属项目，模型路由缺锚点，按未命中语义回落");
        }
        MockLlmFallbackGenerateReply reply;
        try {
            // segment 7a context leg: contract schema + masked recorded few-shots
            // + the lane's session exchanges ride the same door. Absence
            // degrades to null/empty — the answer stands on the plain identity,
            // never vetoed by missing context.
            String schema = contextSupply.contractSchemaJson(
                    projectId, request.method(), request.path());
            List<Map<String, Object>> fewShots = contextSupply.fewShotSamples(
                    request.spaceId(), request.method(), request.path());
            String sessionSummary = contextSupply.sessionSummary(
                    request.spaceId(), request.sessionId());
            reply = fallbackClient.generate(new MockLlmFallbackGenerateInput(
                    request.spaceId(), projectId, request.method(), request.path(),
                    request.maskedFingerprint(), schema, fewShots, sessionSummary));
        } catch (Exception ex) {
            log.warn("mock llm-fallback generation leg failed for space {}: {}",
                    request.spaceId(), ex.getMessage());
            return MockLlmFallbackAnswer.miss(cacheKey,
                    "模型代答通道当前不可用（" + ex.getClass().getSimpleName() + "），按未命中语义回落");
        }
        if (reply == null || reply.body() == null
                || reply.status() < 100 || reply.status() > 599) {
            return MockLlmFallbackAnswer.miss(cacheKey,
                    "模型代答产物不完整（缺正文或状态越界），拒绝充当兜底应答，按未命中语义回落");
        }
        return new MockLlmFallbackAnswer(true, false, reply.status(),
                reply.headers() == null ? Map.of() : reply.headers(),
                reply.body(), Math.max(0, reply.tokensSpent()), null, cacheKey);
    }

    /** Owning project of the space row = LLM routing anchor (single lean read). */
    private Long findSpaceProjectId(long spaceId) {
        var rows = jdbc.query(
                "SELECT project_id FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource().addValue("id", spaceId),
                (rs, n) -> rs.getLong("project_id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Payload shape guard: only artifacts that could serve honestly may store. */
    private void validatePayload(MockLlmFallbackStoreRequest request) {
        if (request.spaceId() == null) {
            throw new ServiceException(MockRuntimeRefusals.fallbackSpaceIdMissing(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.status() < 100 || request.status() > 599) {
            throw new ServiceException(MockRuntimeRefusals.fallbackStatusOutOfRange(request.status()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.body() == null) {
            throw new ServiceException(MockRuntimeRefusals.fallbackBodyNull(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** TTL horizon from the K8 key; <=0 stores NULL (never expires by age). */
    private LocalDateTime expiresHorizon() {
        int hours = configReader.getInt(TTL_KEY, DEFAULT_TTL_HOURS);
        return hours <= 0 ? null : LocalDateTime.now().plusHours(hours);
    }

    /** The {status,headers,body} object the read side's isObject gate accepts. */
    private String snapshotJson(MockLlmFallbackStoreRequest request) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("status", request.status());
            snapshot.put("headers", request.headers() == null
                    ? Map.of() : request.headers());
            snapshot.put("body", request.body());
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new ServiceException(
                    MockRuntimeRefusals.fallbackSnapshotUnserializable(ex.getMessage()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String normalizeMethod(String method) {
        return method == null ? "GET" : method.trim().toUpperCase();
    }

    /** Dual gate, master first — an open sub-switch never overrides a closed master. */
    private void assertGatesOpen() {
        if (!configReader.getBoolean(MASTER_KEY, false)) {
            throw gateRefusal(MASTER_KEY, "Mock 模块 AI 总闸");
        }
        if (!configReader.getBoolean(SUB_KEY, false)) {
            throw gateRefusal(SUB_KEY, "应用点「llm-fallback」分开关");
        }
    }

    private ServiceException gateRefusal(String key, String label) {
        return new ServiceException(MockRuntimeRefusals.fallbackGateClosed(label, key),
                BizErrorCode.FORBIDDEN);
    }

    /** Fresh row fetch: expires_at in the future (or never) and not expired by age. */
    private CacheRow findFresh(String cacheKey, long spaceId) {
        var rows = jdbc.query("""
                SELECT id, response_snapshot::text AS snapshotJson
                FROM tp_mock_llm_cache
                WHERE space_id = :spaceId AND cache_key = :cacheKey
                  AND (expires_at IS NULL OR expires_at > :now)
                LIMIT 1
                """,
                new MapSqlParameterSource()
                        .addValue("spaceId", spaceId)
                        .addValue("cacheKey", cacheKey)
                        .addValue("now", LocalDateTime.now()),
                (rs, n) -> new CacheRow(rs.getLong("id"), rs.getString("snapshotJson")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** One statement, one truth: the row served, the row's ledger moves. */
    private void recordHit(long rowId) {
        jdbc.update("""
                UPDATE tp_mock_llm_cache
                SET hit_count = hit_count + 1, last_hit_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", rowId)
                .addValue("now", LocalDateTime.now()));
    }

    /** Snapshot plane: {status,headers,body} as written by V241's column contract. */
    private JsonNode parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            // a scalar/array parses fine yet carries no {status,headers,body} —
            // serving it would invent a 200 out of nothing, same refusal as garbage
            return node.isObject() ? node : null;
        } catch (Exception ex) {
            // a corrupt artifact is never served — the caller degrades to miss
            log.warn("mock llm-fallback cache row carries an unparseable snapshot: {}",
                    ex.getMessage());
            return null;
        }
    }

    private String readBody(JsonNode snapshot) {
        JsonNode body = snapshot.path("body");
        return body.isTextual() ? body.asText() : body.toString();
    }

    private Map<String, String> readHeaders(JsonNode snapshot) {
        Map<String, String> headers = new LinkedHashMap<>();
        snapshot.path("headers").fields().forEachRemaining(
                entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

    /** Projection of one fresh cache row (record per layering rules — no inner class). */
    private record CacheRow(long id, String snapshotJson) {
    }
}
