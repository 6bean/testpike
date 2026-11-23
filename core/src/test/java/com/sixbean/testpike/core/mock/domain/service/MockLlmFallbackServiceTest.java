package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackAnswer;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateInput;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateReply;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreResult;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.infrastructure.remote.client.AiServiceMockLlmFallbackClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-3 LLM fallback answer plane, read side .
 * <p>Contract under test: the master gate {@code ai.mock.enabled} is ANDed with
 * the per-point sub-switch {@code ai.mock.llm-fallback}; a closed gate refuses
 * in plain words naming the exact key (same spectrum as the drafting-job
 * gates), and the refusal is a control answer — it never pretends a serving
 * outcome. With both gates open: a fresh cache row for the space-scoped
 * masked fingerprint answers instantly with zero token spend and bumps the
 * row's hit ledger (hit_count + last_hit_at); an expired row is never served
 * (stale generated artifacts are as dishonest as a fabricated default); a
 * cold space answers an honest miss — never a 500 dressed up as a fallback.
 * <p>Real dev database, zero mocks (MockAiJobServiceTest posture); gates are
 * hot config rewritten through ConfigCacheHolder with values restored in
 * finally. The generation leg (ai-service sync endpoint + cache write) is
 * and is deliberately absent here: the miss path must stand on its
 * own before anything can spend tokens.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockLlmFallbackServiceTest {

    private static final String MASTER_KEY = "ai.mock.enabled";
    private static final String SUB_KEY = "ai.mock.llm-fallback";
    private static final String BUDGET_KEY = "mock.llm-fallback.max-tokens-per-response";
    private static final String TTL_KEY = "mock.llm-fallback.cache-ttl-hours";

    /**
     * ai-service generation leg isolated at L2 (RestAiServiceNlStepClient
     * family precedent): the mock stands in for the OUTBOUND HTTP leg only —
     * every DB, gate and cache behavior stays real, and the live chain is
     * verified at L3 (red line 3).
     */
    @MockitoBean
    private AiServiceMockLlmFallbackClient fallbackClient;

    @Autowired
    private MockLlmFallbackService fallbackService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newSpace() {
        String code = "t366p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
        String key = "t366-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(projectId,
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
        return spaceId;
    }

    private void seedCache(long spaceId, String cacheKey, boolean fresh, int tokens) {
        jdbcTemplate.update("""
                INSERT INTO tp_mock_llm_cache (space_id, cache_key, method, path,
                    response_snapshot, tokens_spent, expires_at)
                VALUES (?, ?, 'GET', '/orders/{id}',
                    CAST('{"status":200,"headers":{"X-Gen":"llm"},"body":"{\\"id\\":7}"}' AS jsonb),
                    ?, ?)
                """, spaceId, cacheKey, tokens,
                fresh ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().plusDays(1)) : null);
        if (!fresh) {
            jdbcTemplate.update("""
                    UPDATE tp_mock_llm_cache SET expires_at = CAST(now() - INTERVAL '1 hour' AS timestamp)
                    WHERE space_id = ? AND cache_key = ?
                    """, spaceId, cacheKey);
        }
    }

    @Test
    void scalarSnapshotRowIsNeverServedAsAnAnswer() {
        // A stored artifact that parses as JSON but is not a {status,headers,body}
        // object is corrupt by contract: serving it would dress a fabricated
        // 200 up as a generated answer (honesty invariant of the cache plane).
        long spaceId = newSpace();
        String key = MockLlmFallbackService.cacheKeyFor("GET", "/orders/7", "fp-" + spaceId);
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            jdbcTemplate.update("""
                    INSERT INTO tp_mock_llm_cache (space_id, cache_key, method, path,
                        response_snapshot, tokens_spent, expires_at)
                    VALUES (?, ?, 'GET', '/orders/{id}', CAST('"oops"' AS jsonb), 5,
                        CAST(now() + INTERVAL '1 hour' AS timestamp))
                    """, spaceId, key);
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/7", "fp-" + spaceId));
            assertFalse(answer.served(),
                    "a scalar snapshot must degrade to honest miss, not a pretend 200");
            assertNotNull(answer.missReason());
            Integer hits = jdbcTemplate.query(
                    "SELECT hit_count FROM tp_mock_llm_cache WHERE space_id = ? AND cache_key = ?",
                    (rs, n) -> rs.getInt(1), spaceId, key).stream().findFirst().orElse(null);
            assertEquals(0, hits, "a refused corrupt artifact must not move the hit ledger");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void closedMasterGateRefusesNamingTheKey() {
        long spaceId = newSpace();
        try {
            configCacheHolder.remove(MASTER_KEY);
            configCacheHolder.put(SUB_KEY, "1");
            ServiceException e = assertThrows(ServiceException.class,
                    () -> fallbackService.answer(new MockLlmFallbackRequest(spaceId,
                            "GET", "/orders/7", "fp-" + spaceId)));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(MASTER_KEY),
                    "refusal must name the master key, got: " + e.getMessage());
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void closedSubSwitchRefusesNamingItsKeyEvenWithMasterOpen() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.remove(SUB_KEY);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> fallbackService.answer(new MockLlmFallbackRequest(spaceId,
                            "GET", "/orders/7", "fp-" + spaceId)));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(SUB_KEY),
                    "refusal must name the sub-switch, got: " + e.getMessage());
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void coldSpaceAnswersHonestMissWithBothGatesOpen() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/7", "fp-cold"));
            assertFalse(answer.served(), "no cache row and no generation leg = honest miss");
            assertFalse(answer.cacheHit());
            assertNotNull(answer.missReason(), "the miss must say why in plain words");
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_mock_llm_cache WHERE space_id = ?",
                    Integer.class, spaceId), "a miss answers nothing and writes nothing");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void freshCacheRowServesInstantlyWithZeroTokenSpendAndHitLedgerBump() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            // The read side answers whatever fingerprint the writer stored —
            // exposes the key through the answer view for pairing.
            MockLlmFallbackRequest request = new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-fresh-" + spaceId);
            seedCache(spaceId, fallbackCacheKey(request), true, 1234);
            MockLlmFallbackAnswer answer = fallbackService.answer(request);
            assertTrue(answer.served(), "a fresh generated answer must serve");
            assertTrue(answer.cacheHit(), "cache reads spend zero tokens by definition");
            assertEquals(0, answer.tokensSpent(), "serving from cache re-pays nothing");
            assertEquals(200, answer.status());
            assertEquals("{\"id\":7}", answer.body());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT hit_count FROM tp_mock_llm_cache WHERE space_id = ? AND cache_key = ?",
                    Integer.class, spaceId, fallbackCacheKey(request)));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT last_hit_at FROM tp_mock_llm_cache WHERE space_id = ? AND cache_key = ?",
                    java.sql.Timestamp.class, spaceId, fallbackCacheKey(request)),
                    "a served answer stamps when it was served");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void expiredCacheRowIsNeverServed() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            MockLlmFallbackRequest request = new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-stale-" + spaceId);
            seedCache(spaceId, fallbackCacheKey(request), false, 99);
            MockLlmFallbackAnswer answer = fallbackService.answer(request);
            assertFalse(answer.served(), "an expired artifact must not masquerade as fresh");
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT hit_count FROM tp_mock_llm_cache WHERE space_id = ? AND cache_key = ?",
                    Integer.class, spaceId, fallbackCacheKey(request)),
                    "an unserved row earns no hit");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void zeroTokenBudgetRefusesServingAsMissNotAsError() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            configCacheHolder.put(BUDGET_KEY, "0");
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/7", "fp-budget"));
            assertFalse(answer.served(), "over-budget falls to miss, never burns tokens");
            assertNotNull(answer.missReason());
        } finally {
            configCacheHolder.remove(BUDGET_KEY);
            cleanup(spaceId);
        }
    }

    @Test
    void storedAnswerIsServedBackThroughTheSameFingerprintKey() {
        // Writer/reader pairing contract): what store persists,
        // answer() must serve on the exact same masked fingerprint — a silent
        // key-scheme divergence would show up here, never as a silent miss.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            MockLlmFallbackStoreResult stored = fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "get", "/orders/{id}",
                            "fp-pair-" + spaceId, 201,
                            Map.of("X-Gen", "llm"), "{\"id\":42}", 800));
            assertTrue(stored.stored(), "a valid paid generation must persist, got: "
                    + stored.reason());
            assertNotNull(stored.expiresAt(), "TTL horizon comes from the K8 key");

            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/{id}",
                            "fp-pair-" + spaceId));
            assertTrue(answer.served(), "store→answer pairs on one key scheme");
            assertTrue(answer.cacheHit());
            assertEquals(0, answer.tokensSpent(), "cache reads re-pay nothing");
            assertEquals(201, answer.status());
            assertEquals("{\"id\":42}", answer.body());
            assertEquals("llm", answer.headers().get("X-Gen"));
            assertEquals(stored.cacheKey(), answer.cacheKey(),
                    "the echoed fingerprint proves writer and reader speak one scheme");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void storeRefusesWhenEitherGateIsClosedNamingTheKey() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.remove(SUB_KEY);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> fallbackService.store(new MockLlmFallbackStoreRequest(spaceId,
                            "GET", "/orders/{id}", "fp-gate", 200, Map.of(), "{}", 10)));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(SUB_KEY),
                    "write side shares the read side's gate spectrum, got: " + e.getMessage());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_mock_llm_cache WHERE space_id = ?",
                    Integer.class, spaceId), "a gate refusal writes nothing");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void storeOverBudgetIsRefusedInPlainWordsAndWritesNothing() {
        // The token ceiling guards BOTH directions: an answer that cost more
        // than the per-response budget never enters the cache as a
        // pre-paid future answer — the refusal is a control result, not a 500.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            configCacheHolder.put(BUDGET_KEY, "500");
            MockLlmFallbackStoreResult result = fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "GET", "/orders/{id}",
                            "fp-over", 200, Map.of(), "{}", 501));
            assertFalse(result.stored(), "over-budget generations are not cased");
            assertNotNull(result.reason(), "the refusal says why in plain words");
            assertTrue(result.reason().contains(BUDGET_KEY),
                    "refusal names the exact key, got: " + result.reason());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_mock_llm_cache WHERE space_id = ?",
                    Integer.class, spaceId));
        } finally {
            configCacheHolder.remove(BUDGET_KEY);
            cleanup(spaceId);
        }
    }

    @Test
    void storeTwiceOnOneFingerprintReplacesTheRowWithoutDuplicating() {
        // The unique (space_id, cache_key) contract: a newer generation of the
        // same question overwrites the stale artifact instead of stacking rows.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            fallbackService.store(new MockLlmFallbackStoreRequest(spaceId, "GET",
                    "/orders/{id}", "fp-upsert", 200, Map.of(), "{\"v\":1}", 100));
            MockLlmFallbackStoreResult second = fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "GET", "/orders/{id}",
                            "fp-upsert", 200, Map.of(), "{\"v\":2}", 200));
            assertTrue(second.stored());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_mock_llm_cache WHERE space_id = ?",
                    Integer.class, spaceId), "one fingerprint, one row");
            MockLlmFallbackAnswer answer = fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-upsert"));
            assertEquals("{\"v\":2}", answer.body(), "the newest artifact serves");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void nonPositiveTtlKeyStoresTheRowAsNeverExpiring() {
        // K8 mock.llm-fallback.cache-ttl-hours <= 0 = "never expires by age"
        // (the V241 column semantics for NULL expires_at), not an error.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            configCacheHolder.put(TTL_KEY, "0");
            MockLlmFallbackStoreResult stored = fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "GET", "/orders/{id}",
                            "fp-nottl", 200, Map.of(), "{}", 10));
            assertTrue(stored.stored());
            assertNull(stored.expiresAt(), "ttl<=0 stores without an age horizon");
            MockLlmFallbackAnswer answer = fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-nottl"));
            assertTrue(answer.served(), "a never-expiring row still serves");
        } finally {
            configCacheHolder.remove(TTL_KEY);
            cleanup(spaceId);
        }
    }

    @Test
    void malformedGenerationPayloadIsRefusedAsValidationError() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            // status outside the HTTP range can never serve honestly later
            assertThrows(ServiceException.class, () -> fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "GET", "/orders/{id}",
                            "fp-bad", 999, Map.of(), "{}", 10)));
            assertThrows(ServiceException.class, () -> fallbackService.store(
                    new MockLlmFallbackStoreRequest(spaceId, "GET", "/orders/{id}",
                            "fp-bad", 200, Map.of(), null, 10)));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_mock_llm_cache WHERE space_id = ?",
                    Integer.class, spaceId));
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void coldMissWithBothGatesOpenEscalatesOnceAndServesThePaidAnswer() {
        // segment 2c contract: an open-gate cold miss escalates to the sync
        // generation leg EXACTLY once, and a fresh generation serves as
        // cacheHit=false with its real token cost visible on the answer.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(201, Map.of("X-Gen", "llm"),
                            "{\"id\":77}", 640, "glm-test", true, null));
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/{id}", "fp-esc"));
            assertTrue(answer.served(), "a paid generation must serve, got miss: "
                    + answer.missReason());
            assertFalse(answer.cacheHit(), "a fresh generation is not a cache read");
            assertEquals(201, answer.status());
            assertEquals("{\"id\":77}", answer.body());
            assertEquals(640, answer.tokensSpent(), "the cost of THIS request rides the answer");
            assertEquals("llm", answer.headers().get("X-Gen"));
            ArgumentCaptor<MockLlmFallbackGenerateInput> sent =
                    ArgumentCaptor.forClass(MockLlmFallbackGenerateInput.class);
            Mockito.verify(fallbackClient).generate(sent.capture());
            assertEquals(spaceId, sent.getValue().spaceId());
            assertEquals("GET", sent.getValue().method());
            assertEquals("/orders/{id}", sent.getValue().path());
            assertEquals("fp-esc", sent.getValue().maskedFingerprint());
            assertNotNull(sent.getValue().projectId(),
                    "the generation leg needs the owning project as LLM routing anchor");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void escalationCarriesContractSchemaAndFewShotSamples() {
        // context leg: the generation payload must carry the
        // endpoint contract schema and masked recorded samples of the same
        // endpoint family — the deterministic drafting contexts the A-3
        // feature spec pins for a schema-honest instant answer.
        long spaceId = newSpace();
        long projectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_mock_space WHERE id = ?",
                Long.class, spaceId);
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            // One endpoint of the project's newest contract: GET /orders/{id}
            // declaring a response schema with a distinctive field name.
            String endpoints = "[{\"method\":\"GET\",\"path\":\"/orders/{id}\","
                    + "\"endpointKey\":\"GET /orders/{id}\",\"operationId\":null,"
                    + "\"title\":\"order detail\",\"description\":null,\"tags\":[],"
                    + "\"parameters\":[],\"requestContentType\":null,\"requestSchema\":null,"
                    + "\"responseSchemas\":{\"200\":\"{\\\"type\\\":\\\"object\\\","
                    + "\\\"properties\\\":{\\\"totalQuantity\\\":{\\\"type\\\":\\\"integer\\\"}}}\"},"
                    + "\"securityRefs\":[],\"structureHash\":null}]";
            jdbcTemplate.update("""
                    INSERT INTO tp_api_spec (project_id, name, spec_format, spec_text,
                        content_sha256, spec_version, endpoints)
                    VALUES (?, ?, 'openapi3-json', 'x', ?, 1, CAST(? AS jsonb))
                    """, projectId, "t366ctx-" + UUID.randomUUID(), "0".repeat(64), endpoints);
            // Masked ledger rows of the same endpoint family, one of them in
            // the session being answered — the few-shot and summary sources.
            String snap = "{\"method\":\"GET\",\"path\":\"/orders/%d\"}";
            String resp = "{\"status\":200,\"headers\":{},\"body\":\"{\\\"totalQuantity\\\":%d}\"}";
            for (int i = 0; i < 2; i++) {
                jdbcTemplate.update("""
                        INSERT INTO tp_mock_hit_log (space_id, request_snapshot,
                            response_snapshot, matched, match_trace, session_id, source, hit_at)
                        VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), true, '[]', 'sess-7a', 'stub', now())
                        """, spaceId, String.format(snap, 7 + i), String.format(resp, 3 + i));
            }
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(200, Map.of(), "{}", 100, "glm-test", false, null));
            fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-ctx"));
            ArgumentCaptor<MockLlmFallbackGenerateInput> sent =
                    ArgumentCaptor.forClass(MockLlmFallbackGenerateInput.class);
            Mockito.verify(fallbackClient).generate(sent.capture());
            MockLlmFallbackGenerateInput ctx = sent.getValue();
            assertNotNull(ctx.contractSchemaJson(),
                    "the endpoint's declared contract must travel to the drafting leg");
            assertTrue(ctx.contractSchemaJson().contains("totalQuantity"),
                    "contract schema content = the declared response shape, got: "
                            + ctx.contractSchemaJson());
            assertFalse(ctx.fewShotSamples().isEmpty(),
                    "masked recorded samples of the family ride as few-shot");
            assertTrue(ctx.fewShotSamples().stream()
                            .anyMatch(s -> String.valueOf(s).contains("totalQuantity")),
                    "few-shot samples carry the recorded response shapes");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_hit_log WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_api_spec WHERE project_id = ?", projectId);
            cleanup(spaceId);
        }
    }

    @Test
    void escalationCarriesSessionExchangesOfTheLane() {
        // session-summary leg: the lane's recent REAL exchanges
        // ride the generation door as plain "METHOD /path -> status" lines.
        // The fallback's own fabrications (source='llm') and rows of other
        // lanes must never leak into the drafting context.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            jdbcTemplate.update("""
                    INSERT INTO tp_mock_hit_log (space_id, request_snapshot,
                        response_snapshot, matched, session_id, source, hit_at)
                    VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), true, 'sess-7b', 'stub', now())
                    """, spaceId, "{\"method\":\"GET\",\"path\":\"/orders/7\"}",
                    "{\"status\":200,\"body\":\"{}\"}");
            jdbcTemplate.update("""
                    INSERT INTO tp_mock_hit_log (space_id, request_snapshot,
                        response_snapshot, matched, session_id, source, hit_at)
                    VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), true, 'sess-7b', 'proxy',
                        now() - interval '1 second')
                    """, spaceId, "{\"method\":\"POST\",\"path\":\"/orders\"}",
                    "{\"status\":201,\"body\":\"{}\"}");
            // a previously fabricated answer of the same lane — never evidence
            jdbcTemplate.update("""
                    INSERT INTO tp_mock_hit_log (space_id, request_snapshot,
                        response_snapshot, matched, session_id, source, hit_at)
                    VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), true, 'sess-7b', 'llm', now())
                    """, spaceId, "{\"method\":\"GET\",\"path\":\"/ghost\"}",
                    "{\"status\":200,\"body\":\"{}\"}");
            // another lane of the same space — never leaks across
            jdbcTemplate.update("""
                    INSERT INTO tp_mock_hit_log (space_id, request_snapshot,
                        response_snapshot, matched, session_id, source, hit_at)
                    VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb), true, 'sess-other', 'stub', now())
                    """, spaceId, "{\"method\":\"GET\",\"path\":\"/elsewhere\"}",
                    "{\"status\":200,\"body\":\"{}\"}");
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(200, Map.of(), "{}", 100, "glm-test", false, null));
            fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-sess", "sess-7b"));
            ArgumentCaptor<MockLlmFallbackGenerateInput> sent =
                    ArgumentCaptor.forClass(MockLlmFallbackGenerateInput.class);
            Mockito.verify(fallbackClient).generate(sent.capture());
            String summary = sent.getValue().sessionSummary();
            assertNotNull(summary, "the lane's exchanges must ride the generation door");
            assertTrue(summary.contains("GET /orders/7 -> 200"),
                    "each exchange is one honest line, got: " + summary);
            assertTrue(summary.contains("POST /orders -> 201"),
                    "real proxied answers count too, got: " + summary);
            assertFalse(summary.contains("/ghost"),
                    "a fabricated answer never counts as a real exchange");
            assertFalse(summary.contains("/elsewhere"),
                    "other lanes never leak into this summary");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_hit_log WHERE space_id = ?", spaceId);
            cleanup(spaceId);
        }
    }

    @Test
    void contextLegAbsenceStillEscalatesWithPlainIdentity() {
        // Supply-side honesty: a space whose project carries no contract and
        // no recorded family still escalates — null schema / empty few-shots,
        // never a refusal invented from missing context.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(200, Map.of(), "{}", 100, "glm-test", false, null));
            MockLlmFallbackAnswer answer = fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/never-contracted/{id}", "fp-noctx"));
            assertTrue(answer.served(), "absent context must not veto the answer leg");
            ArgumentCaptor<MockLlmFallbackGenerateInput> sent =
                    ArgumentCaptor.forClass(MockLlmFallbackGenerateInput.class);
            Mockito.verify(fallbackClient).generate(sent.capture());
            assertNull(sent.getValue().contractSchemaJson());
            assertTrue(sent.getValue().fewShotSamples().isEmpty());
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void generationLegFailureDegradesToOriginalMissNeverAnError() {
        // Red-line honesty: a broken provider leg is NOT the traffic plane's
        // problem — the caller still gets its 404+trace semantics, expressed
        // here as an unserved answer with plain-word reason.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            Mockito.when(fallbackClient.generate(Mockito.any()))
                    .thenThrow(new IllegalStateException("ai-service is down"));
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/{id}", "fp-boom"));
            assertFalse(answer.served(), "a failed provider never fakes an answer");
            assertNotNull(answer.missReason(), "the miss says why in plain words");
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void incompleteGenerationReplyIsNeverServedAsAnAnswer() {
        // A reply without a body (or with a status outside 100..599) is a
        // broken artifact — serving it would invent a response the model
        // never paid for; degrade to the honest miss instead.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(200, Map.of(), null, 10, "m", false, null));
            assertFalse(fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-null-body")).served());
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(999, Map.of(), "{}", 10, "m", false, null));
            assertFalse(fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-bad-status")).served());
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void refusedStoreStillServesThePaidAnswerWithHonestProvenance() {
        // The bookkeeping verdict never claws back an answer that was already
        // paid for: stored=false from the store door relays through, the
        // caller still deserves its response relay contract).
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            Mockito.when(fallbackClient.generate(Mockito.any())).thenReturn(
                    new MockLlmFallbackGenerateReply(200, Map.of(), "{}", 5000,
                            "glm-test", false, "generation cost exceeds the per-response budget"));
            MockLlmFallbackAnswer answer = fallbackService.answer(
                    new MockLlmFallbackRequest(spaceId, "GET", "/orders/{id}", "fp-refused"));
            assertTrue(answer.served(), "an over-budget store still served what it paid");
            assertFalse(answer.cacheHit());
            assertEquals(5000, answer.tokensSpent());
        } finally {
            cleanup(spaceId);
        }
    }

    @Test
    void zeroTokenBudgetAndClosedGatesNeverTouchTheGenerationLeg() {
        // Nothing may burn tokens behind a closed gate or a zero budget: the
        // escalation leg must see ZERO interactions in both shapes.
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SUB_KEY, "1");
            configCacheHolder.put(BUDGET_KEY, "0");
            assertFalse(fallbackService.answer(new MockLlmFallbackRequest(
                    spaceId, "GET", "/orders/{id}", "fp-noleg")).served());
            Mockito.verifyNoInteractions(fallbackClient);
        } finally {
            configCacheHolder.remove(BUDGET_KEY);
            cleanup(spaceId);
        }
        long closed = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.remove(SUB_KEY);
            assertThrows(ServiceException.class, () -> fallbackService.answer(
                    new MockLlmFallbackRequest(closed, "GET", "/orders/{id}", "fp-gate")));
            Mockito.verifyNoInteractions(fallbackClient);
        } finally {
            cleanup(closed);
        }
    }

    /** Same fingerprint key the service builds — pinned here so a silent key
     *  scheme change shows up as a failing test instead of a cache that never hits. */
    private String fallbackCacheKey(MockLlmFallbackRequest request) {
        return MockLlmFallbackService.cacheKeyFor(request.method(), request.path(),
                request.maskedFingerprint());
    }

    private void cleanup(long spaceId) {
        jdbcTemplate.update("DELETE FROM tp_mock_llm_cache WHERE space_id = ?", spaceId);
        jdbcTemplate.update("""
                DELETE FROM tp_mock_space WHERE id = ?
                """, spaceId);
        configCacheHolder.remove(SUB_KEY);
        configCacheHolder.remove(MASTER_KEY);
    }
}
