package com.sixbean.testpike.api.perf.ci.api.controller;

import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import com.sixbean.testpike.api.perf.ci.api.dto.PerfCiRunInput;
import com.sixbean.testpike.api.perf.ci.infrastructure.remote.client.PerfCiClient;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI perf-run intake orchestration unit tests : the
 * three /open/perf/ci doors ride the shared CiBearerGate (scope=ci, one
 * spectrum with /open/ci), an idempotent launch keyed under the {@code perfc:}
 * namespace, and pass-through reads of core verdict/view doors (the door
 * computes nothing — single judge head stays in core).
 * <p>Orchestration surface = real CiBearerGate with a verify stub (isolates the
 * core token call) + real Redis IdempotentGuard (same baseline as
 * CiRunsControllerTest) + a recording PerfCiClient stub; the live api→core hop
 * is covered by the L3 script (verification layers are never substituted).
 * <p>Asserted: launch forwards all four arguments with operator = principal id;
 * same idempotency key replays without re-executing; missing key or missing
 * body fields are plain 400s with zero core calls; status and verdict doors
 * pass core payloads through untouched; insufficient scope is a 403 naming the
 * ci scope with zero core calls.
 */
class PerfCiControllerTest {

    /** Verify stub: token always resolves to user 4242 with ci scope. */
    private static final class StubVerifyClient implements ApiTokenVerifyClient {
        @Override
        public McpVerifyOutcome verify(String plaintext) {
            return McpVerifyOutcome.ok(4242L, List.of("ci"));
        }
    }

    /** scope list without ci — drives the 403 branch. */
    private static final class StubScopeClient implements ApiTokenVerifyClient {
        @Override
        public McpVerifyOutcome verify(String plaintext) {
            return McpVerifyOutcome.ok(4242L, List.of("mcp.tools"));
        }
    }

    /** Recording bridge stub: captures arguments, returns controllable values. */
    private static final class StubPerfCiClient implements PerfCiClient {
        final List<String> calls = new ArrayList<>();
        long lastProjectId;
        long lastScenarioId;
        Long lastEnvId;
        long lastOperator;
        long launchedRunId = 501L;

        @Override
        public long launch(long projectId, long scenarioId, Long envId, long operatorId) {
            calls.add("launch:" + projectId + ":" + scenarioId + ":" + envId);
            this.lastProjectId = projectId;
            this.lastScenarioId = scenarioId;
            this.lastEnvId = envId;
            this.lastOperator = operatorId;
            return launchedRunId;
        }

        @Override
        public Map<String, Object> view(long runId, long projectId) {
            calls.add("view:" + runId + ":" + projectId);
            return Map.of("id", runId, "status", 2, "stopReason", "NATURAL_END",
                    "mark", "GOLDEN");
        }

        @Override
        public Map<String, Object> ciVerdict(long runId, long projectId) {
            calls.add("ciVerdict:" + runId + ":" + projectId);
            return Map.of("verdict", "fail", "breachedGates", List.of("p95"),
                    "mark", "GOLDEN");
        }
    }

    private static StringRedisTemplate redis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private final StubPerfCiClient client = new StubPerfCiClient();
    private final PerfCiController controller = new PerfCiController(
            new CiBearerGate(new McpBearerAuthenticator(new StubVerifyClient())),
            client, new IdempotentGuard(redis()));

    private static String freshKey() {
        return "t378d-perfc-" + UUID.randomUUID();
    }

    @Test
    void launch_forwardsFourArgs_withOperatorFromPrincipal() {
        Response<Map<String, Object>> resp = controller.run("Bearer tp_pat_x", freshKey(),
                new PerfCiRunInput(7L, 21L, 3L));
        assertEquals(7L, client.lastProjectId);
        assertEquals(21L, client.lastScenarioId);
        assertEquals(3L, client.lastEnvId);
        assertEquals(4242L, client.lastOperator, "operator = principal.userId() forwarded to core launch");
        assertEquals(501L, ((Number) resp.getData().get("runId")).longValue());
        assertEquals(List.of("launch:7:21:3"), client.calls);
    }

    @Test
    void launch_optionalEnvIdAbsent_forwardsNullAndStillOneCall() {
        controller.run("Bearer tp_pat_x", freshKey(), new PerfCiRunInput(7L, 21L, null));
        assertEquals(List.of("launch:7:21:null"), client.calls,
                "absent envId = null pass-through (scenario default environment decided in core)");
    }

    @Test
    void sameIdempotencyKey_replaysWithoutSecondLaunch() {
        String key = freshKey();
        controller.run("Bearer tp_pat_x", key, new PerfCiRunInput(8L, 22L, null));
        Response<Map<String, Object>> replay = controller.run("Bearer tp_pat_x", key,
                new PerfCiRunInput(8L, 22L, null));
        assertEquals(1, client.calls.size(), "same key inside the window must not launch twice");
        assertEquals(501L, ((Number) replay.getData().get("runId")).longValue(),
                "replay returns the first result body");
    }

    @Test
    void missingIdempotencyKey_400NamesTheHeader_withZeroCalls() {
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", null, new PerfCiRunInput(9L, 23L, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("Idempotency-Key"),
                "plain wording must name the missing header: " + ex.getMessage());
        assertTrue(client.calls.isEmpty(), "missing key must not reach the bridge");
    }

    @Test
    void missingProjectOrScenario_400NamesTheField_withZeroCalls() {
        ServiceException ex1 = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", freshKey(), new PerfCiRunInput(null, 23L, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex1.getCode());
        assertTrue(ex1.getMessage().contains("projectId"), ex1.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", freshKey(), new PerfCiRunInput(9L, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex2.getCode());
        assertTrue(ex2.getMessage().contains("scenarioId"), ex2.getMessage());

        assertTrue(client.calls.isEmpty(), "invalid intake must not reach the bridge");
    }

    @Test
    void statusDoor_passesCoreViewThrough() {
        Response<Map<String, Object>> resp = controller.status("Bearer tp_pat_x", 66L, 5L);
        assertEquals(List.of("view:66:5"), client.calls);
        Map<String, Object> data = resp.getData();
        assertEquals(2, data.get("status"));
        assertEquals("NATURAL_END", data.get("stopReason"));
        assertEquals("GOLDEN", data.get("mark"));
    }

    @Test
    void verdictDoor_passesCoreJudgeThrough_withoutLocalComputation() {
        Response<Map<String, Object>> resp = controller.verdict("Bearer tp_pat_x", 88L, 5L);
        assertEquals(List.of("ciVerdict:88:5"), client.calls);
        Map<String, Object> data = resp.getData();
        assertEquals("fail", data.get("verdict"), "core judge result forwarded as-is");
        assertEquals(List.of("p95"), data.get("breachedGates"));
    }

    @Test
    void tokenWithoutCiScope_403_withZeroCalls() {
        PerfCiController gated = new PerfCiController(
                new CiBearerGate(new McpBearerAuthenticator(new StubScopeClient())),
                client, new IdempotentGuard(redis()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gated.verdict("Bearer x", 1L, 1L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("ci"), ex.getMessage());
        assertTrue(client.calls.isEmpty(), "unauthenticated intake must not reach the bridge");
    }
}
