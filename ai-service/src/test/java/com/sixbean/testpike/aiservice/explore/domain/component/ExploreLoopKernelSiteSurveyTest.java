package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Whole-site survey kernel branch : when the session
 * goal carries the whole-site dialect, the loop never asks the model — it
 * reads the dispatch-time rules snapshot from core, submits exactly one
 * deterministic crawl envelope through the shared explore-act door, waits for
 * the terminal task state on a deadline-derived budget and replays each
 * site-survey-page journal frame back through the single step door so the
 * core ledger relay (SiteSurveyRunLedger) keeps its per-page tally and the
 * collector keeps its single ingest path.
 * <p>Assertion faces: zero LLM calls and zero call-log rows on this branch;
 * envelope shape (kind/action/url/siteSurvey snapshot pass-through); frame
 * mapping (seq order, stepAction name+arguments url/status/reason/counters,
 * candidatePoolJson from the frame detail, pool-less frames stay null);
 * honest terminals (read-path unreachable and missing snapshot degrade to
 * partial without touching the LLM); the poll budget follows the frozen
 * deadlineSeconds with the shipped safety margin.
 * <p>L2 infrastructure isolation follows ExploreLoopKernelTest: core HTTP
 * endpoints are a JDK HttpServer stub, CoreGateway is the real gateway body;
 * LLM chain collaborators are mocks precisely because this branch must prove
 * they are never reached.
 */
class ExploreLoopKernelSiteSurveyTest {

    private static final long SESSION_ID = 88L;
    private static final long PROJECT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;

    private static final List<String> ACT_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATS_BODIES = new CopyOnWriteArrayList<>();
    /** Snapshot stub body for GET /internal/site-survey-runs/by-session/{id}. */
    private static volatile String rulesJson = ok(
            "{\"rulesJson\":\"{\\\"startUrl\\\":\\\"https://shop.test/pr/\\\","
                    + "\\\"maxPages\\\":50,\\\"deadlineSeconds\\\":300,"
                    + "\\\"viewportClasses\\\":[\\\"desktop\\\",\\\"tablet\\\"]}\"}");
    /** Task read-path stub: queue non-empty = dequeue per round, else taskJson. */
    private static volatile String taskJson;
    private static final Deque<String> TASK_JSON_QUEUE = new ArrayDeque<>();
    /** When true the task read path answers an unreadable body (unreachable face). */
    private static volatile boolean taskReadBroken;

    private LlmFallbackChain llmChain;
    private AiCallLogWriter callLog;
    private ExploreLoopKernel kernel;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/internal/explore/sessions/",
                ExploreLoopKernelSiteSurveyTest::handleSessions);
        server.createContext("/internal/site-survey-runs/",
                ExploreLoopKernelSiteSurveyTest::handleRules);
        server.createContext("/internal/executors/tasks/",
                ExploreLoopKernelSiteSurveyTest::handleExecTask);
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handleRules(HttpExchange exchange) throws IOException {
        ExploreStubHttp.writeJson(exchange, rulesJson);
    }

    private static void handleExecTask(HttpExchange exchange) throws IOException {
        if (taskReadBroken) {
            ExploreStubHttp.writeRaw(exchange, "not-a-response");
            return;
        }
        String next = TASK_JSON_QUEUE.isEmpty() ? taskJson : TASK_JSON_QUEUE.poll();
        ExploreStubHttp.writeJson(exchange, next);
    }

    private static void handleSessions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        if (path.endsWith("/acts")) {
            ACT_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, ok("{\"code\":\"0\",\"msg\":\"ok\",\"data\":9001}"));
            return;
        }
        if (path.endsWith("/steps")) {
            STEP_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, ok("{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}"));
            return;
        }
        if (path.endsWith("/status")) {
            STATUS_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, ok("{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}"));
            return;
        }
        if (path.endsWith("/stats")) {
            STATS_BODIES.add(body);
            ExploreStubHttp.writeJson(exchange, ok("{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}"));
            return;
        }
        ExploreStubHttp.writeJson(exchange, ok("{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}"));
    }

    private static String ok(String json) {
        return json;
    }

    @BeforeEach
    void setUp() {
        ACT_BODIES.clear();
        STEP_BODIES.clear();
        STATUS_BODIES.clear();
        STATS_BODIES.clear();
        TASK_JSON_QUEUE.clear();
        taskReadBroken = false;
        rulesJson = ok("{\"rulesJson\":\"{\\\"startUrl\\\":\\\"https://shop.test/pr/\\\","
                + "\\\"maxPages\\\":50,\\\"deadlineSeconds\\\":300,"
                + "\\\"viewportClasses\\\":[\\\"desktop\\\",\\\"tablet\\\"]}\"}");
        taskJson = buildTaskJson(3, null);
        llmChain = mock(LlmFallbackChain.class);
        callLog = mock(AiCallLogWriter.class);
        kernel = new ExploreLoopKernel(
                llmChain,
                mock(ProjectLlmRouter.class),
                mock(ExploreBuiltinToolCatalog.class),
                mock(LlmFunctionCalling.class),
                mock(ToolDispatcher.class),
                mock(ExploreEvidenceGateway.class),
                new CoreGateway(baseUrl),
                callLog,
                MAPPER,
                mock(LoopContextFitting.class),
                mock(ToolResultBudgetFolder.class));
    }

    @AfterEach
    void assertLlmNeverTouched() {
        // chat's projectId slot is a primitive long — any() unboxes to null,
        // anyLong() is the honest matcher for this face.
        verify(llmChain, never()).chat(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(callLog, never()).insert(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static ExploreSessionBrief siteSession() {
        return new ExploreSessionBrief(SESSION_ID, PROJECT_ID,
                "整站测绘：42 https://shop.test/pr/", "https://shop.test/pr/",
                1, 0, 0, null, null, ACTOR_ID, "{}");
    }

    /** One page frame (detail carries url/status/reason/counters and optional pool). */
    private static Map<String, Object> pageFrame(int stepNo, String url, String pageStatus,
                                                 Map<String, Object> counters,
                                                 List<Map<String, Object>> pool) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("url", url);
        detail.put("status", pageStatus);
        detail.put("reason", "ok".equals(pageStatus) ? "" : "被重定向（登录态可能不足或无权）");
        detail.put("counters", counters);
        if (pool != null) {
            // Real-wire shape (executor single source, ): the pool rides
            // the frame detail as the serialized candidate envelope TEXT
            // ({"candidates": [...], "count": n}) — the same payload the
            // single-page element-candidates door journals. An array node
            // here would diverge from production (this fixture drift once
            // let the kernel relay drop every pool on the real chain).
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("candidates", pool);
            envelope.put("count", pool.size());
            try {
                detail.put("candidatePool", MAPPER.writeValueAsString(envelope));
            } catch (Exception serialization) {
                throw new IllegalStateException(serialization);
            }
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("stepNo", stepNo);
        frame.put("action", "site-survey-page");
        frame.put("status", "ok".equals(pageStatus) ? "ok" : "warn");
        try {
            frame.put("detail", MAPPER.writeValueAsString(detail));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return frame;
    }

    private static String buildTaskJson(int status, String errorDetail,
                                        List<Map<String, Object>>... timelines) {
        try {
            List<Map<String, Object>> timeline =
                    timelines.length > 0 ? timelines[0] : List.of();
            return ExploreTaskRowFixture.taskRowJson(status, errorDetail,
                    MAPPER.writeValueAsString(timeline),
                    "[\"1/explore/202611/shot-site-9001.png\"]", 4321,
                    "2026-11-22T10:00:00", "2026-11-22T10:01:00", "2026-11-22T09:59:59");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------- zero-LLM drive

    @Test
    void wholeSiteGoalSubmitsOneDeterministicEnvelopeAndSkipsTheModel() throws Exception {
        taskJson = buildTaskJson(3, null, List.of(
                pageFrame(1, "https://shop.test/pr/", "ok",
                        Map.of("visited", 1, "ok", 1), List.of(Map.of("tag", "button")))));

        kernel.run(siteSession(), ACTOR_ID);

        assertEquals(1, ACT_BODIES.size(), "exactly one crawl envelope per run");
        JsonNode body = MAPPER.readTree(ACT_BODIES.get(0));
        JsonNode envelope = MAPPER.readTree(body.path("actionJson").asText());
        assertEquals("explore-act", envelope.path("kind").asText());
        assertEquals("site-survey", envelope.path("action").asText());
        assertEquals("https://shop.test/pr/", envelope.path("url").asText());
        JsonNode rules = envelope.path("siteSurvey");
        assertEquals(50, rules.path("maxPages").asInt());
        assertEquals(300, rules.path("deadlineSeconds").asInt());
        assertEquals("desktop", rules.path("viewportClasses").get(0).asText());
        // Terminal success of the crawl closes the session as goal-reached.
        assertEquals(1, STATUS_BODIES.size());
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2")
                || STATUS_BODIES.get(0).contains("\"status\": 2"));
    }

    @Test
    void pageFramesReplayThroughTheSingleStepDoor() throws Exception {
        taskJson = buildTaskJson(3, null, List.of(
                pageFrame(1, "https://shop.test/pr/", "ok",
                        Map.of("visited", 1, "ok", 1), List.of(Map.of("tag", "button"))),
                pageFrame(2, "https://shop.test/pr/order/7", "ok",
                        Map.of("visited", 2, "ok", 2), List.of(Map.of("tag", "input"))),
                pageFrame(3, "https://shop.test/pr/private", "failed_login",
                        Map.of("visited", 3, "ok", 2, "failedLogin", 1), null)));

        kernel.run(siteSession(), ACTOR_ID);

        assertEquals(3, STEP_BODIES.size());
        JsonNode first = MAPPER.readTree(
                MAPPER.readTree(STEP_BODIES.get(0)).path("stepAction").asText() == null
                        ? "{}" : MAPPER.readTree(STEP_BODIES.get(0)).path("stepAction").isTextual()
                        ? MAPPER.readTree(STEP_BODIES.get(0)).path("stepAction").asText()
                        : STEP_BODIES.get(0));
        // stepAction rides the ledger relay contract: name + arguments face.
        JsonNode step0 = MAPPER.readTree(STEP_BODIES.get(0));
        JsonNode action0 = step0.path("stepAction").isTextual()
                ? MAPPER.readTree(step0.path("stepAction").asText()) : step0.path("stepAction");
        assertEquals("site-survey-page", action0.path("name").asText());
        assertEquals("https://shop.test/pr/", action0.path("arguments").path("url").asText());
        assertEquals("ok", action0.path("arguments").path("status").asText());
        // Fixture correction (assertion-lag face): the previous two lines were
        // degenerate ternaries with identical branches whose expected constant
        // contradicted seq==1 asserted in the same block. Pinned contract per
        // the dispatch ruling: seq mirrors the frame stepNo (1-based,
        // contiguous) and the cumulative counters ride the arguments face.
        assertEquals(1, action0.path("arguments").path("counters").path("visited").asInt(),
                "cumulative counters ride the frame arguments face");
        assertEquals(1, step0.path("seq").asInt());
        assertEquals(3, MAPPER.readTree(STEP_BODIES.get(2)).path("seq").asInt(),
                "seq is 1-based and contiguous with the frame stepNo");
        assertNotNull(first);
        // Candidate pools reach the collector through the dedicated pool field;
        // the login-wall frame carries none (honest failure stays pool-less).
        assertTrue(step0.path("candidatePoolJson").asText().contains("button"));
        JsonNode step2 = MAPPER.readTree(STEP_BODIES.get(2));
        JsonNode action2 = step2.path("stepAction").isTextual()
                ? MAPPER.readTree(step2.path("stepAction").asText()) : step2.path("stepAction");
        assertEquals("failed_login", action2.path("arguments").path("status").asText());
        assertTrue(step2.path("candidatePoolJson").isNull()
                || step2.path("candidatePoolJson").asText().isBlank());
        // Ledger stats: only successfully visited pages count toward pagesVisited.
        assertEquals(1, STATS_BODIES.size());
        assertTrue(STATS_BODIES.get(0).contains("2"),
                "two ok pages on the books for this crawl");
    }

    // ------------------------------------------------------------ honest terminals

    @Test
    void readPathUnreachableDegradesToPartialWithoutSteps() {
        taskReadBroken = true;

        kernel.run(siteSession(), ACTOR_ID);

        assertEquals(1, ACT_BODIES.size(), "the crawl was still submitted once");
        assertTrue(STEP_BODIES.isEmpty(), "no frames readable = no step facts minted");
        assertEquals(1, STATUS_BODIES.size());
        assertTrue(STATUS_BODIES.get(0).contains("4"),
                "unreachable read path closes the session as partial, never done");
    }

    @Test
    void missingRunSnapshotStopsHonestlyBeforeAnyCrawl() {
        // The rules door answers a business error (no ledger row for session).
        rulesJson = "{\"code\":\"40401\",\"msg\":\"no run\","
                + "\"data\":{\"rulesJson\":null}}";

        kernel.run(siteSession(), ACTOR_ID);

        assertTrue(ACT_BODIES.isEmpty(), "no envelope may be submitted without the snapshot");
        assertTrue(STEP_BODIES.isEmpty());
        assertEquals(1, STATUS_BODIES.size());
        assertTrue(STATUS_BODIES.get(0).contains("4"),
                "the session closes partial with the guidance carried in the report");
    }

    @Test
    void failedTaskClosesPartialEvenWhenFramesLanded() throws Exception {
        taskJson = buildTaskJson(4, "整站测绘失败：3 页异常 / 5 页尝试",
                List.of(pageFrame(1, "https://shop.test/pr/", "ok",
                        Map.of("visited", 1, "ok", 1), List.of(Map.of("tag", "a")))));

        kernel.run(siteSession(), ACTOR_ID);

        assertEquals(1, STEP_BODIES.size(), "landed frames are still journaled");
        assertEquals(1, STATUS_BODIES.size());
        assertTrue(STATUS_BODIES.get(0).contains("4"));
    }

    // ------------------------------------------------------------- poll budget face

    @Test
    void pollCapFollowsFrozenDeadlineWithShippedMargin() throws Exception {
        JsonNode rules = MAPPER.readTree(
                "{\"deadlineSeconds\":300,\"maxPages\":50}");
        long cap = ExploreLoopKernel.siteSurveyPollCapMs(rules);
        assertEquals(ExploreLoopKernel.SITE_POLL_MARGIN_MS + 300_000L, cap);

        JsonNode bare = MAPPER.readTree("{\"maxPages\":50}");
        assertEquals(ExploreLoopKernel.SITE_POLL_MARGIN_MS
                + ExploreLoopKernel.SITE_POLL_DEFAULT_DEADLINE_MS,
                ExploreLoopKernel.siteSurveyPollCapMs(bare),
                "a snapshot without the deadline falls back to the shipped default");
        assertEquals(ExploreLoopKernel.SITE_POLL_MARGIN_MS
                        + ExploreLoopKernel.SITE_POLL_DEFAULT_DEADLINE_MS,
                ExploreLoopKernel.siteSurveyPollCapMs(null));
    }
}
