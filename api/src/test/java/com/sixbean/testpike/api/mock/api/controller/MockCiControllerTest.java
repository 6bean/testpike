package com.sixbean.testpike.api.mock.api.controller;

import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import com.sixbean.testpike.api.mock.api.dto.MockCiExportView;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceCreateInput;
import com.sixbean.testpike.api.mock.api.dto.MockCiSpaceInput;
import com.sixbean.testpike.api.mock.infrastructure.remote.client.CoreMockCiClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI mock doors api): three /open/mock/ci endpoints behind the shared
 * scope=ci gate.
 * <p>Orchestration under test = CiBearerGate (real component + verify stub that
 * isolates the core token check) → idempotency-Key intake rule →
 * IdempotentGuard (real Redis 6379, same base as CiRunsControllerTest — idempotency
 * bookkeeping must not be mocked) → recording stub for CoreMockCiClient. The live
 * api→core HTTP chain is covered later by the L3 segment (verification levels are
 * not skippable, this file only proves the orchestration shape).
 * <p>Asserted behaviours: ①space create stamps projectId/ttlHours/name-defaulting and
 * forwards principal.userId as operator; ②same idempotency key replays the first
 * result with zero re-execution; ③missing Idempotency-Key is a plain-word 400 with
 * zero execution; ④blank spaceKey is rejected before the bridge is touched;
 * ⑤export door passes format through and returns the dialect body with its honesty
 * notes untouched; ⑥hit-count door passes since/matched through raw (zero
 * reprocessing, single-point validation lives in core); ⑦a token without the ci
 * scope gets 403, not 401 (self-service diagnosis).
 */
class MockCiControllerTest {

    /** Verify stub: scope list configurable so the 401/403 split is testable. */
    private static final class StubVerifyClient implements ApiTokenVerifyClient {
        List<String> scopes = List.of("ci");

        @Override
        public McpVerifyOutcome verify(String plaintext) {
            return McpVerifyOutcome.ok(4242L, scopes);
        }
    }

    /** Core bridge recording stub: captures intake, returns controllable values. */
    private static final class StubCiClient implements CoreMockCiClient {
        final List<String> calls = new ArrayList<>();
        long lastProjectId;
        Integer lastTtlHours;
        MockCiSpaceInput lastInput;
        long lastOperator;
        long nextSpaceId = 501L;
        long lastExportSpaceId;
        String lastExportFormat;
        MockCiExportView exportResult =
                new MockCiExportView("mockoon", "{\"root\":[]}", List.of("note-1"));
        long lastHitsSpaceId;
        String lastSince;
        Boolean lastMatched;
        long hitsCount = 3L;

        @Override
        public long createSpace(long projectId, Integer ttlHours, MockCiSpaceInput input,
                                long operatorId) {
            calls.add("createSpace:" + projectId);
            this.lastProjectId = projectId;
            this.lastTtlHours = ttlHours;
            this.lastInput = input;
            this.lastOperator = operatorId;
            return nextSpaceId++;
        }

        @Override
        public MockCiExportView exportSpace(long spaceId, String format) {
            calls.add("exportSpace:" + spaceId);
            this.lastExportSpaceId = spaceId;
            this.lastExportFormat = format;
            return exportResult;
        }

        @Override
        public long countHits(long spaceId, String since, Boolean matched) {
            calls.add("countHits:" + spaceId);
            this.lastHitsSpaceId = spaceId;
            this.lastSince = since;
            this.lastMatched = matched;
            return hitsCount;
        }
    }

    private static StringRedisTemplate redis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private final StubVerifyClient verifyClient = new StubVerifyClient();
    private final StubCiClient ciClient = new StubCiClient();
    private final MockCiController controller = new MockCiController(
            new CiBearerGate(new McpBearerAuthenticator(verifyClient)),
            ciClient, new IdempotentGuard(redis()));

    private static String freshKey() {
        return "it-mockci-" + UUID.randomUUID();
    }

    @Test
    void createSpace_defaultsNameFromKey_andForwardsProjectTtlOperator() {
        Response<Map<String, Object>> resp = controller.createSpace("Bearer tp_pat_x",
                freshKey(), 7L, new MockCiSpaceCreateInput(null, "ci-run-42", 3));
        assertEquals(7L, ciClient.lastProjectId, "projectId rides the query as core declares");
        assertEquals(3, ciClient.lastTtlHours, "ttlHours passes through to the core TTL stamp");
        assertEquals("ci-run-42", ciClient.lastInput.name(),
                "omitted name defaults to the routing key — never a blank");
        assertEquals("ci-run-42", ciClient.lastInput.spaceKey());
        assertEquals(4242L, ciClient.lastOperator, "operator=principal.userId() for core audit");
        assertEquals(501L, ((Number) resp.getData().get("spaceId")).longValue());
        assertEquals(List.of("createSpace:7"), ciClient.calls);
    }

    @Test
    void sameIdempotencyKey_replaysFirstResult_withZeroReExecution() {
        String key = freshKey();
        controller.createSpace("Bearer tp_pat_x", key, 8L,
                new MockCiSpaceCreateInput("nightly", "ci-replay", null));
        Response<Map<String, Object>> replay = controller.createSpace("Bearer tp_pat_x", key,
                8L, new MockCiSpaceCreateInput("nightly", "ci-replay", null));
        assertEquals(List.of("createSpace:8"), ciClient.calls,
                "second call replays the stored result, no second space born");
        assertEquals(501L, ((Number) replay.getData().get("spaceId")).longValue(),
                "replayed body carries the first space id");
        assertNull(ciClient.lastTtlHours, "omitted ttl stays null (never expires, UI posture)");
    }

    @Test
    void missingIdempotencyKey_isPlainWordFourHundred_withZeroExecution() {
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSpace(
                "Bearer tp_pat_x", null, 9L,
                new MockCiSpaceCreateInput(null, "ci-nok", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("Idempotency-Key"),
                "plain words name the missing header: " + ex.getMessage());
        assertTrue(ciClient.calls.isEmpty(), "missing key must not touch the bridge at all");
    }

    @Test
    void blankSpaceKey_isRejectedBeforeTheBridge() {
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.createSpace(
                "Bearer tp_pat_x", freshKey(), 9L,
                new MockCiSpaceCreateInput("name", "  ", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("spaceKey"),
                "plain words name the offending field: " + ex.getMessage());
        assertTrue(ciClient.calls.isEmpty(), "shape gate is intake-only; core owns the full rule");
    }

    @Test
    void exportDoor_passesFormat_andReturnsDialectBodyWithNotesUntouched() {
        Response<MockCiExportView> resp = controller.exportSpace("Bearer tp_pat_x", 12L,
                "wiremock");
        assertEquals(12L, ciClient.lastExportSpaceId);
        assertEquals("wiremock", ciClient.lastExportFormat);
        assertEquals("mockoon", resp.getData().format(), "core verdict is law, api zero recompute");
        assertEquals(List.of("note-1"), resp.getData().notes(),
                "the honesty loss-list must survive the bridge intact");
    }

    @Test
    void hitCountDoor_passesSinceAndMatchedThroughRaw() {
        Response<Long> resp = controller.countHits("Bearer tp_pat_x", 13L,
                "2026-10-16T00:00:00", Boolean.TRUE);
        assertEquals(13L, ciClient.lastHitsSpaceId);
        assertEquals("2026-10-16T00:00:00", ciClient.lastSince,
                "ISO string travels raw — parsing single point is core");
        assertEquals(Boolean.TRUE, ciClient.lastMatched);
        assertEquals(3L, resp.getData().longValue());
    }

    @Test
    void tokenWithoutCiScope_getsForbiddenNotUnauthorized() {
        verifyClient.scopes = List.of("mcp.read");
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.countHits(
                "Bearer tp_pat_x", 14L, null, null));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "scope miss is 403, not 401");
        assertTrue(ciClient.calls.isEmpty(), "gate runs before any bridge call");
    }
}
