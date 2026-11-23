package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.QueryResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.QueryTargetViewRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aggregate-query dialog tool contract : the
 * synthetic read-only FC tool that answers "how many / how distributed" in
 * the live dialog. Same L2 posture as LlmFunctionCallingTest — the tool talks
 * to a local core-response stub over real HTTP (environment stub, not a
 * business mock), so the whole remote shape is exercised.
 * <p>Pinned behaviours: ① the tool definition names the callable targets the
 * registry door advertises (model accuracy) while NEVER leaking physical
 * table names; ② execution hands the model's raw arguments to the core
 * interpreter and returns the server-computed body verbatim (the live card
 * renders from this body, the model never typesets numbers); ③ a core refusal
 * becomes a readable failure message instead of an exception (fail-soft,
 * ToolDispatcher semantics); ④ non-JSON arguments are refused before any door
 * is touched; ⑤ a broken targets door degrades to a generic description —
 * the dialog keeps answering honestly without target names rather than dying.
 */
class AggregateQueryToolTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger runHits = new AtomicInteger();
    private static final AtomicInteger targetHits = new AtomicInteger();
    private static final Deque<String> runBodies = new ArrayDeque<>();
    private static volatile String lastOperatorHeader;
    private static final Long ACTOR = 7L;

    /** Path-scoped canned responses; default branch = registry-complete answers. */
    private static volatile String runResponse = """
            {"code":"0","msg":"ok","data":{"target":"test_case",
             "columns":["status","cnt"],"rows":[{"status":1,"cnt":7}],
             "rowLimitUsed":200}}""";
    private static volatile String targetsResponse = """
            {"code":"0","msg":"ok","data":[
              {"targetKey":"test_case","dimensionColumns":"status,priority",
               "measureColumns":"review_fail_cnt","maxRows":200},
              {"targetKey":"defect","dimensionColumns":"severity,status",
               "measureColumns":"occurrence_count","maxRows":100}]}""";

    private ObjectMapper mapper;
    private CoreGateway gateway;
    private AggregateQueryTool tool;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", AggregateQueryToolTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        lastOperatorHeader = exchange.getRequestHeaders().getFirst("X-Operator-Id");
        String body;
        if (path.equals("/internal/query-spec/run")) {
            runHits.incrementAndGet();
            runBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            body = runResponse;
        } else if (path.equals("/internal/query-spec/targets")) {
            targetHits.incrementAndGet();
            body = targetsResponse;
        } else {
            body = "{\"code\":\"40401\",\"msg\":\"未知门\",\"data\":null}";
        }
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        gateway = new CoreGateway(baseUrl);
        tool = new AggregateQueryTool(gateway, mapper);
        runHits.set(0);
        targetHits.set(0);
        runBodies.clear();
    }

    @AfterEach
    void resetCanned() {
        runResponse = """
                {"code":"0","msg":"ok","data":{"target":"test_case",
                 "columns":["status","cnt"],"rows":[{"status":1,"cnt":7}],
                 "rowLimitUsed":200}}""";
        targetsResponse = """
                {"code":"0","msg":"ok","data":[
                  {"targetKey":"test_case","dimensionColumns":"status,priority",
                   "measureColumns":"review_fail_cnt","maxRows":200},
                  {"targetKey":"defect","dimensionColumns":"severity,status",
                   "measureColumns":"occurrence_count","maxRows":100}]}""";
    }

    @Test
    void definitionNamesRegisteredTargetsWithoutPhysicalTables() {
        ChatToolDef def = tool.toolDef(ACTOR);

        assertEquals(AggregateQueryTool.NAME, def.function().name());
        String description = def.function().description();
        assertNotNull(description);
        assertTrue(description.contains("test_case"), description);
        assertTrue(description.contains("defect"), description);
        assertFalse(description.contains("tp_test_case"),
                "physical table names must not reach the model: " + description);
        // parameters = the QuerySpec DSL shape (target/filters/groupBy/
        // aggregations/orderBy/limit), object type.
        assertEquals("object", def.function().parameters().path("type").asText());
        assertTrue(def.function().parameters().path("properties").has("target"),
                def.function().toString());
        assertTrue(def.function().parameters().path("properties").has("aggregations"),
                def.function().toString());
    }

    @Test
    void definitionWithoutActorDegradesToGenericShapeButKeepsTheTool() {
        ChatToolDef def = tool.toolDef(null);

        assertEquals(AggregateQueryTool.NAME, def.function().name());
        assertNotNull(def.function().description());
        assertEquals(0, targetHits.get(),
                "no identity means no vocabulary round trip at all");
    }

    @Test
    void executionHandsRawArgumentsToTheDoorAndReturnsTheComputedBody() {
        ToolCallOutcome outcome = tool.execute(3L, 7L,
                "{\"target\":\"test_case\",\"groupBy\":[\"status\"],"
                        + "\"aggregations\":[{\"function\":\"count\",\"column\":null}]}");

        assertTrue(outcome.success(), outcome.content());
        assertEquals(1, runHits.get());
        // Arguments ride inside the spec envelope untouched (one read face,
        // no re-serialization on the way).
        assertTrue(runBodies.peek().contains("\"groupBy\":[\"status\"]"), runBodies.peek());
        // The answer body is what the live card renders — columns/rows verbatim.
        assertTrue(outcome.content().contains("\"cnt\":7"), outcome.content());
        assertEquals("7", lastOperatorHeader);
    }

    @Test
    void coreRefusalBecomesReadableToolMessage() {
        runResponse = "{\"code\":\"40001\",\"msg\":\"只读查询目标「ghost」不在登记表内\","
                + "\"data\":null}";

        ToolCallOutcome outcome = tool.execute(3L, 7L, "{\"target\":\"ghost\"}");

        assertFalse(outcome.success());
        // The interpreter's sentence (target named) survives to the model —
        // the model can self-correct against the registry it was shown.
        assertTrue(outcome.content().contains("不在登记表内"), outcome.content());
    }

    @Test
    void invalidArgumentsAreRefusedBeforeAnyDoorIsTouched() {
        ToolCallOutcome outcome = tool.execute(3L, 7L, "not-json{{");

        assertFalse(outcome.success());
        assertEquals(0, runHits.get(), "an unparsable body never reaches the interpreter door");
    }

    @Test
    void missingActorIsRefusedBeforeAnyDoorIsTouched() {
        // The core door fails closed on anonymous identity; ai-service says it
        // locally so the model sees the reason without a wasted round trip.
        ToolCallOutcome outcome = tool.execute(3L, null, "{\"target\":\"test_case\"}");

        assertFalse(outcome.success());
        assertEquals(0, runHits.get());
    }

    @Test
    void brokenTargetsDoorDegradesToGenericDescriptionInsteadOfDying() {
        targetsResponse = "not-json{{";

        ChatToolDef def = tool.toolDef(ACTOR);

        // Fail-soft (same no-break-the-chain posture as ToolDispatcher): the
        // dialog keeps the tool,
        // just without the target vocabulary.
        assertEquals(AggregateQueryTool.NAME, def.function().name());
        assertNotNull(def.function().description());
        assertFalse(def.function().description().contains("tp_"));
    }

    @Test
    void remoteMirrorsMatchTheCoreDoorFieldNames() {
        // Contract mirror (ai-service must not depend on core): field-name drift
        // is the historical silent-null trap, pinned by explicit construction.
        QueryResultRemote result = new QueryResultRemote("test_case",
                List.of("status", "cnt"), List.of(Map.of("cnt", 7)), 200);
        assertEquals("test_case", result.target());
        assertEquals(200, result.rowLimitUsed());
        QueryTargetViewRemote view = new QueryTargetViewRemote("defect",
                "severity", "occurrence_count", 100);
        assertEquals("occurrence_count", view.measureColumns());
    }
}
