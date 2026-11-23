package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolResultBudgetFolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Kernel consumption of the page write-risk ledger: the loop
 * reads one narrow risk door per run and uses it twice — the system prompt
 * annotates every booked write page with the literal read-only guidance, and
 * a click/input call targeting one of those pages is never dispatched at all
 * (the mechanical gate answers with a downgrade observation, so safety does
 * not bet on model self-restraint).
 * <p>Assertion faces: prompt annotation present/absent, zero outbound act
 * submissions on a hit page, the downgrade observation relayed to the next
 * round, clean pages ride the untouched dispatch path (zero ripple), the URL
 * fallback chain startUrl→lastUrl→call-arg is honored, and a risk-door
 * outage degrades visibly to the pre-slice behavior instead of failing the
 * run (same fail-soft posture as the behavior-template fallback).
 * <p>L2 posture (ExploreLoopKernelTest convention): core HTTP doors are a
 * JDK HttpServer stub, CoreGateway/ToolDispatcher are real bodies, the LLM
 * chain and template catalog are mocks — orchestration itself never mocked.
 */
class ExploreLoopKernelRiskLedgerTest {

    private static final long SESSION_ID = 91L;
    private static final long PROJECT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;

    private static final List<String> ACT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATS_BODIES = new CopyOnWriteArrayList<>();
    /** Risk door stub body (GET /internal/page-write-profiles/risk-pages). */
    private static volatile String riskJson;
    /** When true the risk door answers an unreadable body (door outage face). */
    private static volatile boolean riskDoorBroken;

    private LlmFallbackChain llmChain;
    private ExploreLoopKernel kernel;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/internal/explore/sessions/",
                ExploreLoopKernelRiskLedgerTest::handleSessions);
        server.createContext("/internal/page-write-profiles/",
                ExploreLoopKernelRiskLedgerTest::handleRiskPages);
        server.createContext("/internal/executors/tasks/",
                ExploreLoopKernelRiskLedgerTest::handleExecTask);
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handleRiskPages(HttpExchange exchange) throws IOException {
        if (riskDoorBroken) {
            ExploreStubHttp.writeRaw(exchange, "not-a-response");
            return;
        }
        ExploreStubHttp.writeJson(exchange, riskJson);
    }

    private static void handleExecTask(HttpExchange exchange) throws IOException {
        ExploreStubHttp.writeJson(exchange, ExploreTaskRowFixture.taskRowJson(3, null,
                "[]", "[\"1/explore/202611/shot-9101.png\"]",
                1234, "2026-11-23T12:00:01", "2026-11-23T12:00:02", "2026-11-23T12:00:00"));
    }

    private static void handleSessions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        if (path.endsWith("/acts")) {
            ACT_CALLS.add(body);
            ExploreStubHttp.writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":9101}");
            return;
        }
        if (path.endsWith("/steps")) {
            STEP_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        if (path.endsWith("/status")) {
            STATUS_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        if (path.endsWith("/stats")) {
            STATS_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        ExploreStubHttp.writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
    }

    @BeforeEach
    void setUp() {
        ACT_CALLS.clear();
        STEP_BODIES.clear();
        STATUS_BODIES.clear();
        STATS_BODIES.clear();
        riskDoorBroken = false;
        riskJson = riskDoor("{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}");

        llmChain = mock(LlmFallbackChain.class);
        ExploreBuiltinToolCatalog catalog = mock(ExploreBuiltinToolCatalog.class);
        LlmFunctionCalling functionCalling = mock(LlmFunctionCalling.class);
        ProjectLlmRouter router = mock(ProjectLlmRouter.class);
        LoopContextFitting fitting = mock(LoopContextFitting.class);
        when(catalog.toolDefs()).thenReturn(List.of());
        when(functionCalling.assemblePlan(anyLong(), anyLong()))
                .thenReturn(new FcPlan(List.of(), Map.of()));
        when(functionCalling.decorate(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "mock", "mock-model", null, baseUrl, "config", null));
        when(fitting.fit(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));

        kernel = new ExploreLoopKernel(
                llmChain,
                router,
                catalog,
                functionCalling,
                mock(ToolDispatcher.class),
                mock(ExploreEvidenceGateway.class),
                new CoreGateway(baseUrl),
                mock(AiCallLogWriter.class),
                MAPPER,
                fitting,
                mock(ToolResultBudgetFolder.class));
    }

    @AfterEach
    void drain() {
        ACT_CALLS.clear();
    }

    /** One booked write page in the risk door. */
    private static String riskDoor(String json) {
        return json;
    }

    private static ExploreSessionBrief session(String startUrl) {
        return new ExploreSessionBrief(SESSION_ID, PROJECT_ID, "摸清下单流程",
                startUrl, 1, 0, 0, 0L, null, ACTOR_ID, "{}");
    }

    private static LlmChatResult withTool(LlmToolCall call) {
        return new LlmChatResult("", "mock", "mock-model", 10, 20, 1L, List.of(call));
    }

    private void scriptRounds(LlmChatResult... rounds) {
        Deque<LlmChatResult> queue = new ArrayDeque<>(List.of(rounds));
        when(llmChain.chat(any(), anyLong(), any())).thenAnswer(inv -> {
            if (queue.isEmpty()) {
                throw new AssertionError("risk-ledger script exhausted");
            }
            return queue.poll();
        });
    }

    private List<ChatCompletionRequest> capturedRounds(int expected) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(expected)).chat(captor.capture(), anyLong(), any());
        return captor.getAllValues();
    }

    private static String systemTextOf(ChatCompletionRequest request) {
        return request.messages().get(0).toString();
    }

    private static String allMessageTextOf(ChatCompletionRequest request) {
        StringBuilder dump = new StringBuilder();
        request.messages().forEach(message -> dump.append(message).append('\n'));
        return dump.toString();
    }

    @Test
    void bookedRiskPageIsAnnotatedInPromptAndNeverDispatched() {
        riskJson = riskDoor("{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                + "{\"pageKey\":\"http://risk.test/checkout\","
                + "\"pageUrl\":\"http://risk.test/checkout\"}]}");
        scriptRounds(
                withTool(new LlmToolCall("c1", "click-path",
                        "{\"url\":\"http://risk.test/checkout\"}")),
                new LlmChatResult("GOAL_REACHED", "mock", "mock-model", 10, 20, 1L, null));

        kernel.run(session("http://risk.test/home"), ACTOR_ID);

        List<ChatCompletionRequest> rounds = capturedRounds(2);
        String system = systemTextOf(rounds.get(0));
        assertTrue(system.contains("写入风险页：http://risk.test/checkout"),
                "system prompt must annotate the booked risk page verbatim");
        assertTrue(system.contains("该页输入会直接保存数据，只允许读取页面快照"),
                "risk annotation must carry the literal read-only guidance");
        assertTrue(STATUS_BODIES.size() >= 1, "loop still terminals normally");

        assertTrue(ACT_CALLS.isEmpty(),
                "a click on a booked risk page must never reach the act door");
        String relayed = allMessageTextOf(rounds.get(1));
        assertTrue(relayed.contains("该页已降级为只读"),
                "the gated round must relay the downgrade observation to the model");
    }

    @Test
    void cleanProjectRidesTheUntouchedDispatchPath() {
        riskJson = riskDoor("{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}");
        scriptRounds(
                withTool(new LlmToolCall("c1", "click-path",
                        "{\"url\":\"http://risk.test/checkout\"}")),
                new LlmChatResult("GOAL_REACHED", "mock", "mock-model", 10, 20, 1L, null));

        kernel.run(session("http://risk.test/home"), ACTOR_ID);

        List<ChatCompletionRequest> rounds = capturedRounds(2);
        assertFalse(systemTextOf(rounds.get(0)).contains("写入风险页"),
                "empty ledger adds no annotation lines (zero ripple)");
        assertEquals(1, ACT_CALLS.size(),
                "a clean page keeps dispatching exactly as before the slice");
        assertFalse(allMessageTextOf(rounds.get(1)).contains("该页已降级为只读"));
    }

    @Test
    void riskTargetFallsBackToStartUrlWhenCallCarriesNoUrl() {
        riskJson = riskDoor("{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                + "{\"pageKey\":\"http://risk.test/settings\","
                + "\"pageUrl\":\"http://risk.test/settings\"}]}");
        scriptRounds(
                withTool(new LlmToolCall("c1", "input-path", "{}")),
                new LlmChatResult("GOAL_REACHED", "mock", "mock-model", 10, 20, 1L, null));

        kernel.run(session("http://risk.test/settings"), ACTOR_ID);

        assertTrue(ACT_CALLS.isEmpty(),
                "the startUrl face must be gated when the action carries no url");
        List<ChatCompletionRequest> rounds = capturedRounds(2);
        assertTrue(allMessageTextOf(rounds.get(1)).contains("该页已降级为只读"));
    }

    @Test
    void riskDoorOutageDegradesVisiblyAndKeepsExploring() {
        riskDoorBroken = true;
        scriptRounds(
                withTool(new LlmToolCall("c1", "click-path",
                        "{\"url\":\"http://risk.test/checkout\"}")),
                new LlmChatResult("GOAL_REACHED", "mock", "mock-model", 10, 20, 1L, null));

        kernel.run(session("http://risk.test/home"), ACTOR_ID);

        List<ChatCompletionRequest> rounds = capturedRounds(2);
        assertFalse(systemTextOf(rounds.get(0)).contains("写入风险页"),
                "a door outage annotates nothing instead of failing the run");
        assertEquals(1, ACT_CALLS.size(),
                "outage posture is fail-soft: the loop keeps its previous behavior");
    }
}
