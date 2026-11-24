package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ExploreLoopKernelTest {

    private static final long SESSION_ID = 77L;
    private static final long PROJECT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;
    private static final List<String> ACT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> ACT_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STEP_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> STATUS_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> STATS_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> GRANT_QUERIES = new CopyOnWriteArrayList<>();
    private static final List<String> EVIDENCE_PUTS = new CopyOnWriteArrayList<>();
    private static final List<String> INVOKE_CALLS = new CopyOnWriteArrayList<>();
 
    private static final List<String> TASK_POLL_CALLS = new CopyOnWriteArrayList<>();
    
    private static volatile String assemblyJson = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}";
    
    private static volatile String taskJson = buildTaskJson(3, null,
            "[\"1/explore/202609/shot-9001.png\"]");
    
    private static final Deque<String> TASK_JSON_QUEUE = new ConcurrentLinkedDeque<>();
    /** Step stub conflict switch (true = core answers 40904 (duplicate seq), simulating a redelivered replay). */
    private static volatile boolean stepConflict;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/internal/explore/sessions/", ExploreLoopKernelTest::handleSessions);
        server.createContext("/internal/tools/", ExploreLoopKernelTest::handleTools);
        server.createContext("/internal/executors/tasks/", ExploreLoopKernelTest::handleExecTask);
        server.createContext("/evidence/put", ExploreLoopKernelTest::handleEvidencePut);
        server.createContext("/internal/page-write-profiles/",
                ExploreLoopKernelTest::handleRiskPages);
        server.createContext("/internal/prompt-templates/", CoreStubHttp::respondAbsentConfig);
        server.createContext("/internal/projects/", CoreStubHttp::respondAbsentConfig);
        server.createContext("/internal/configs/", CoreStubHttp::respondAbsentConfig);
        server.start();
    }

    /** Write-risk door stub (GET /internal/page-write-profiles/risk-pages):
     * an empty ledger keeps the clean-page path intact for these cases. */
    private static void handleRiskPages(HttpExchange exchange) throws IOException {
        writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}");
    }

    private static void handleExecTask(HttpExchange exchange) throws IOException {
        TASK_POLL_CALLS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        String next = TASK_JSON_QUEUE.isEmpty() ? taskJson : TASK_JSON_QUEUE.poll();
        writeJson(exchange, next);
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        ACT_CALLS.clear();
        ACT_BODIES.clear();
        STEP_CALLS.clear();
        STEP_BODIES.clear();
        STATUS_CALLS.clear();
        STATUS_BODIES.clear();
        STATS_BODIES.clear();
        GRANT_QUERIES.clear();
        EVIDENCE_PUTS.clear();
        INVOKE_CALLS.clear();
        TASK_POLL_CALLS.clear();
        TASK_JSON_QUEUE.clear();
        assemblyJson = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}";
        taskJson = buildTaskJson(3, null, "[\"1/explore/202609/shot-9001.png\"]");
        stepConflict = false;
    }

    
    private static String buildTaskJson(int status, String errorDetail, String screenshotsJson) {
        return buildTaskJson(status, errorDetail, screenshotsJson, null, null);
    }

 
    private static String buildTaskJson(int status, String errorDetail, String screenshotsJson,
                                        String timelineArtifactKey, String evidenceScreenshotKey) {
        try {
            Map<String, Object> timelineEntry = new LinkedHashMap<>();
            timelineEntry.put("stepNo", 1);
            timelineEntry.put("action", "snapshot");
            timelineEntry.put("startAt", "2026-09-14T15:00:00");
            timelineEntry.put("endAt", "2026-09-14T15:00:01");
            timelineEntry.put("status", "ok");
            timelineEntry.put("detail", "页面下单列表可见提交按钮");
            timelineEntry.put("screenshotKey", null);
            if (timelineArtifactKey != null) {
                timelineEntry.put("artifactKey", timelineArtifactKey);
            }
            if (evidenceScreenshotKey != null) {
                timelineEntry.put("evidence", Map.of("screenshot", evidenceScreenshotKey));
            }
            return ExploreTaskRowFixture.taskRowJson(status, errorDetail,
                    MAPPER.writeValueAsString(List.of(timelineEntry)), screenshotsJson,
                    1234, "2026-09-14T15:00:00", "2026-09-14T15:00:01", "2026-09-14T14:59:59");
        } catch (Exception e) {
            throw new IllegalStateException("桩 JSON 组装失败", e);
        }
    }

    private static void handleSessions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/acts")) {
            ACT_CALLS.add(exchange.getRequestMethod() + " " + path);
            ACT_BODIES.add(readBody(exchange));
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":9001}");
            return;
        }
        if (path.endsWith("/steps")) {
            STEP_CALLS.add(exchange.getRequestMethod() + " " + path);
            STEP_BODIES.add(readBody(exchange));
            if (stepConflict) {
                writeJson(exchange, "{\"code\":\"40904\",\"msg\":\"步序 1 已存在（重复回写）\""
                        + ",\"data\":null}");
                return;
            }
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        if (path.endsWith("/status")) {
            STATUS_CALLS.add(exchange.getRequestMethod() + " " + path);
            STATUS_BODIES.add(readBody(exchange));
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        if (path.endsWith("/stats")) {
            STATS_BODIES.add(readBody(exchange));
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}");
            return;
        }
        if (path.endsWith("/evidence-grant")) {
            GRANT_QUERIES.add(exchange.getRequestURI().getRawQuery());
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"sessionId\":"
                    + SESSION_ID + ",\"objectKey\":\"1/explore/202609/abc-"
                    + Math.abs(GRANT_QUERIES.size()) + "-dom.txt\",\"uploadUrl\":\""
                    + baseUrl + "/evidence/put\"}}");
            return;
        }
        writeJson(exchange, "{\"code\":\"40401\",\"msg\":\"未预期路径 " + path + "\",\"data\":null}");
    }

    private static void handleTools(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/assembly")) {
            writeJson(exchange, assemblyJson);
            return;
        }
        INVOKE_CALLS.add(exchange.getRequestMethod() + " " + path);
        readBody(exchange);
        writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"toolId\":11,"
                + "\"toolName\":\"kb_http\",\"success\":true,\"output\":\"{\\\"data\\\":1}\","
                + "\"errorCategory\":null,\"errorMessage\":null,\"errorCode\":null,\"durationMs\":9}}");
    }

    private static void handleEvidencePut(HttpExchange exchange) throws IOException {
        EVIDENCE_PUTS.add(exchange.getRequestMethod() + " "
                + exchange.getRequestHeaders().getFirst("Content-Type"));
        readBody(exchange);
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    private final AiCallLogWriter aiCallLog = mock(AiCallLogWriter.class);
    private final Deque<LlmChatResult> script = new ArrayDeque<>();
    private final List<ChatCompletionRequest> sentRequests = new ArrayList<>();

    private ExploreLoopKernel kernel() {
        // Single build point (R40): the no-seam case is the explicit identity
        // fitting, never a second constructor copy of the collaborator list.
        return kernel(com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE);
    }

    /** Same kernel with an explicit per-round budget fitting seam (AP-2 seam test). */
    private ExploreLoopKernel kernel(
            com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting fitting) {
        return kernel(fitting, null);
    }

    /** Kernel with an explicit tool-result budget folder seam (AP-1 built-in fold test). */
    private ExploreLoopKernel kernel(
            com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting fitting,
            com.sixbean.testpike.aiservice.tool.domain.component.ToolResultBudgetFolder folder) {
        CoreGateway gateway = new CoreGateway(baseUrl);
        LlmProperties routerProps = new LlmProperties();
        routerProps.setModel("route-test-model");
        return new ExploreLoopKernel(chain,
                new ProjectLlmRouter(gateway, routerProps),
                new ExploreBuiltinToolCatalog(MAPPER),
                new LlmFunctionCalling(gateway, MAPPER),
                new ToolDispatcher(gateway, MAPPER),
                new ExploreEvidenceGateway(gateway, RestClient.builder()),
                gateway, aiCallLog, MAPPER, fitting, folder);
    }

    /**
 * AP-2 per-round seam : the
 * budget fitting must run before EVERY provider round (one judgement per
 * round, never one pre-loop pass), receive the decorated packet plus the
 * project segment, and what the provider finally gets must be the fitted
 * output — a silently bypassed seam (regression to NONE, or a fit call moved
 * out of the round loop) turns this red.
 */
    @Test
    void 每轮发送前各判一次预算装配缝_发送包必为装配产出() {
        List<ChatCompletionRequest> fittedPackets = new ArrayList<>();
        List<Long> fittedProjects = new ArrayList<>();
        com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting seamSpy =
                (request, projectId) -> {
                    fittedPackets.add(request);
                    fittedProjects.add(projectId);
                    List<ChatMessage> marked = new ArrayList<>(request.messages());
                    ChatMessage head = marked.get(0);
                    marked.set(0, new ChatMessage(head.role(),
                            head.textContent() + "[fit" + fittedPackets.size() + "]"));
                    return new ChatCompletionRequest(request.model(), List.copyOf(marked),
                            request.temperature(), request.maxTokens(), request.tools(),
                            request.toolChoice());
                };
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot",
                "{\"url\":\"http://localhost:5173/list\"}")));
        script.add(finalAnswer("GOAL_REACHED 两轮循环已验证"));

        kernel(seamSpy).run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(2, sentRequests.size(), "夹具=两轮（动作轮+达成轮），实际出站=" + sentRequests.size());
        assertEquals(2, fittedPackets.size(),
                "预算装配判定必须逐轮各判一次（两轮=两次，禁循环外一次性判定），实际=" + fittedPackets.size());
        assertEquals(List.of(PROJECT_ID, PROJECT_ID), fittedProjects,
                "装配判定必须携项目切片（预算键按项目路由读取）");
        assertNotNull(fittedPackets.get(0).tools(),
                "判定发生在装饰后的包上（FC 工具声明已在位），否则超限包体估算失真");
        assertTrue(sentRequests.get(0).messages().get(0).textContent().contains("[fit1]"),
                "第 1 轮出站包=装配产出，实际=" + sentRequests.get(0).messages().get(0).textContent());
        assertTrue(sentRequests.get(1).messages().get(0).textContent().contains("[fit2]"),
                "第 2 轮出站包=该轮装配产出（禁复用首轮结果），实际="
                        + sentRequests.get(1).messages().get(0).textContent());
    }

    /**
     * Budget-refuse posture (CTXCURVE r9 root cause, L2): the per-round
     * LoopContextBudgetGate throws ServiceException(41301) when a packet stays
     * over the prompt limit after folding. Letting that refuse propagate out of
     * the consumer hands it to the stateless MQ retry, which re-runs the whole
     * exploration and dies on the duplicate step write-back — the session never
     * reaches a terminal state and the refusal never reaches the report. The
     * kernel must terminalize the run HERE (status + stats report naming the
     * refusal reason) so the message is acked exactly once.
     */
    @Test
    void budgetRefuseTerminalizesRunInsteadOfPropagatingToMqRetry() {
        LoopContextFitting refusing = (request, projectId) -> {
            throw new ServiceException("对话上下文超出模型上限（实际 99999 tokens > 上限 1），"
                    + "无法继续本轮对话，请新开对话或缩短本轮输入", BizErrorCode.AI_PROMPT_TOO_LARGE);
        };
        kernel(refusing).run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);
        assertEquals(1, STATUS_BODIES.size(), "拒止必须终态封转（status 回写恰一次）");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":4"),
                "预算拒止=部分完成终态 4，实际=" + STATUS_BODIES.get(0));
        assertEquals(1, STATS_BODIES.size(), "终态前 stats 必回写报告");
        assertTrue(STATS_BODIES.get(0).contains("本次自动浏览提前结束"),
                "报告必须点名提前结束的原因（用户可读面），实际=" + STATS_BODIES.get(0));
        verify(chain, never()).chat(any(), anyLong(), any());
    }

    /**
     * Step write-back re-entry (CTXCURVE r9 second defect): when a retried or
     * replayed delivery re-records a seq core already journaled, core answers
     * 40904 (duplicate step seq). That is NOT a run failure — the step fact is on the
     * books — so the kernel skips the duplicate write and keeps looping.
     * Propagating it would wedge every transient mid-run failure into the DLQ
     * with the session stuck non-terminal.
     */
    @Test
    void duplicateStepWriteBackIsSkippedAndRunContinues() {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot",
                "{\"url\":\"http://localhost:5173/list\"}")));
        script.add(finalAnswer("GOAL_REACHED 重复步序已跳过"));
        stepConflict = true;

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(1, STATUS_BODIES.size(), "冲突不得炸单，终态仍要封转");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"),
                "目标达成终态 2，实际=" + STATUS_BODIES.get(0));
    }

    /**
     * Seam-less default = send-as-built identity fitting: the kernel built
     * with LoopContextFitting.NONE must hand the provider exactly what the
     * loop built, keeping existing fixtures byte-compatible with no fold section.
     */
    @Test
    void 旧构造按原包发送不经折叠() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED 单轮即终"));

        kernel().run(session(null), ACTOR_ID);

        assertEquals(1, sentRequests.size(), "单轮即终，实际=" + sentRequests.size());
        String head = sentRequests.get(0).messages().get(0).textContent();
        assertTrue(head.indexOf("[fit") < 0 && head.indexOf("已压缩") < 0,
                "缺省装配=原包发送（NONE 恒等），禁出现折叠段，实际=" + head);
    }

    private void scriptChain() {
        when(chain.chat(any(), anyLong(), any())).thenAnswer(inv -> {
            sentRequests.add(inv.getArgument(0));
            LlmChatResult next = script.poll();
            if (next == null) {
                throw new AssertionError("供应商链路脚本耗尽（禁发起下一轮的断言依据）");
            }
            return next;
        });
    }

    
    private static ExploreSessionBrief session(String budgetSnapshot) {
        return new ExploreSessionBrief(SESSION_ID, PROJECT_ID, "摸清下单流程",
                "http://localhost:5173", 1, 0, 0, 0L, null, ACTOR_ID, budgetSnapshot);
    }

    private static ToolFcAssemblyRemoteItem item(long id, String name, int kind, boolean write) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, kind, "{\"type\":\"object\"}", 5000, write);
    }

    private static void setAssembly(ToolFcAssemblyRemoteItem... items) throws Exception {
        assemblyJson = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":"
                + MAPPER.writeValueAsString(List.of(items)) + "}";
    }

    private static LlmChatResult withTools(LlmToolCall... calls) {
        return new LlmChatResult("", "mock", "mock-model", 10, 20, 1L, List.of(calls));
    }

    private static LlmChatResult finalAnswer(String content) {
        return new LlmChatResult(content, "mock", "mock-model", 10, 20, 1L, null);
    }

    
    @Test
    void 内置动作走提交链路_步进留证回填_goal判done() throws Exception {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot",
                "{\"url\":\"http://localhost:5173/list\"}")));
        script.add(finalAnswer("GOAL_REACHED 列表页搜索已验证"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(List.of("POST /internal/explore/sessions/77/acts"), ACT_CALLS,
                "内置探索动作必须 POST acts 提交链路（core ExploreActInput 契约）");
        String actBody = ACT_BODIES.get(0);
        assertTrue(actBody.contains("\"actionJson\""), "体线名=actionJson，实际=" + actBody);
        assertTrue(actBody.contains("explore-act"), "信封 kind=explore-act，实际=" + actBody);
        assertTrue(actBody.contains("page-snapshot"), "信封 action 透传，实际=" + actBody);
        assertTrue(actBody.contains("http://localhost:5173/list"), "信封 url 透传，实际=" + actBody);

        assertEquals(1, STEP_BODIES.size(), "一步一步进回写");
        String step = STEP_BODIES.get(0);
        assertTrue(step.contains("\"seq\":1"), "seq=1，实际=" + step);
        assertTrue(step.contains("page-snapshot"), "stepAction 含动作名");
        assertTrue(step.contains("domSnapshotKey"), "domSnapshotKey 经留证网关回填");
        assertTrue(step.contains("abc-1-dom.txt"), "key=grant 签发 objectKey 原样");
        JsonNode rec = MAPPER.readTree(step);
        assertEquals("1/explore/202609/shot-9001.png", rec.path("screenshotKey").asText(null),
                "screenshotKey=screenshots 数组首键真回填（读路径），实际=" + step);
        JsonNode res = MAPPER.readTree(rec.path("stepResult").asText());
        assertTrue(res.path("ok").asBoolean(), "stepResult 观测 ok=true，实际=" + res);
        assertTrue(res.path("observation").asText().contains("页面下单列表可见提交按钮"),
                "stepsTimeline detail 组装进观测回填 LLM，实际=" + res);
        assertEquals(1, TASK_POLL_CALLS.size(),
                "提交后必须轮询读路径一次（终态即停轮），实际=" + TASK_POLL_CALLS);
        assertEquals("GET /internal/executors/tasks/9001", TASK_POLL_CALLS.get(0),
                "读路径=既有 core GET /internal/executors/tasks/{id}（零 core 改动）");
        assertEquals(1, EVIDENCE_PUTS.size(), "留证 PUT 真直传一次");
        assertTrue(GRANT_QUERIES.get(0).contains("kind=dom_snapshot"),
                "grant query 钉 kind，实际=" + GRANT_QUERIES.get(0));

        assertEquals(List.of("PUT /internal/explore/sessions/77/status"), STATUS_CALLS);
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"),
                "goalReached=done(2)，实际=" + STATUS_BODIES.get(0));
        assertEquals(1, STATS_BODIES.size(), "出口前 stats 回写报告（预算尽≠无报告）");
        assertTrue(STATS_BODIES.get(0).contains("reportMd"), "部分/完整报告经 stats 链路落库");
    }

    /**
     * AP-1(1) built-in act fold seam (L4 r19 defect: anchored=0/33): the
     * page-snapshot observation backfills OUTSIDE the dispatcher, so it must
     * still pass the single tool-block budget gate — an oversized observation
     * reaches the wire as a head/tail window carrying the retrieval anchor,
     * and the verbatim full text goes to the externalizer seam.
     */
    @Test
    void 内置动作超长观测经预算折叠_上屏tool报文携anchor指针() throws Exception {
        AiCtxConfigResolver resolver = mock(AiCtxConfigResolver.class);
        when(resolver.toolBlockMaxTokens()).thenReturn(120);
        java.util.concurrent.atomic.AtomicReference<String> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.sixbean.testpike.aiservice.tool.domain.component.ToolResultExternalizer
                externalizer = (full, taskId, projectId) -> {
                    stored.set(full);
                    return "anch-kernel-77";
                };
        com.sixbean.testpike.aiservice.tool.domain.component.ToolResultBudgetFolder folder =
                new com.sixbean.testpike.aiservice.tool.domain.component
                        .ToolResultBudgetFolder(resolver,
                        java.util.Optional.of(externalizer));
        TASK_JSON_QUEUE.add(buildTaskJson(3, "X".repeat(4000),
                "[\"1/explore/202609/shot-9001.png\"]"));
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot",
                "{\"url\":\"http://localhost:5173/list\"}")));
        script.add(finalAnswer("GOAL_REACHED 折叠观测已核验"));

        kernel(com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE,
                folder).run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        String toolWire = sentRequests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> "tool".equals(m.role()))
                .map(m -> m.content() == null ? "" : m.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(toolWire.contains("全文已落盘 anchor=anch-kernel-77"),
                "超限内置观测必须折叠后上屏并携 anchor 指针，实际=" + toolWire);
        assertNotNull(stored.get(), "externalizer 必须收到折叠前全文");
        assertTrue(stored.get().contains("XXXX"), "落盘全文=观测原文（含失败详情体）");
    }

    
    @Test
    void goal优先于其余判型_达成轮不执行动作() {
        scriptChain();
        script.add(new LlmChatResult("GOAL_REACHED", "mock", "mock-model", 10, 20, 1L,
                List.of(new LlmToolCall("c1", "assert", "{\"url\":\"http://a/x\"}"))));

        kernel().run(session("{\"maxSteps\":1,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(0, ACT_CALLS.size(), "达成优先=该轮动作不执行");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"),
                "达成优先=done(2)，实际=" + STATUS_BODIES.get(0));
    }

    
    @Test
    void 预算步数截断_budget_stop且不再续跑() {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot", "{\"url\":\"http://a/1\"}")));
        script.add(withTools(new LlmToolCall("c2", "page-snapshot", "{\"url\":\"http://a/2\"}")));

        kernel().run(session("{\"maxSteps\":2,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(2, ACT_CALLS.size(), "maxSteps=2 截断=恰 2 动作");
        assertEquals(2, STEP_CALLS.size(), "步进恰 2 条");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":3"),
                "预算耗尽=budget-stop(3)≠失败，实际=" + STATUS_BODIES.get(0));
    }

 
    @Test
    void 连续同页达阈_判partial() {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "click-path", "{\"url\":\"http://a/same\"}")));
        script.add(withTools(new LlmToolCall("c2", "click-path", "{\"url\":\"http://a/same\"}")));
        script.add(withTools(new LlmToolCall("c3", "click-path", "{\"url\":\"http://a/same\"}")));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":2}"),
                ACTOR_ID);

        assertEquals(3, ACT_CALLS.size(), "阈=2：第 1 步计新页，第 2/3 步同页达阈终止");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":4"),
                "无进展=partial(4)，实际=" + STATUS_BODIES.get(0));
    }

    
    @Test
    void 项目工具走dispatcher_不进探索提交链路() throws Exception {
        setAssembly(item(11, "kb_http", 1, false));
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}")));
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(List.of("POST /internal/tools/11/invoke"), INVOKE_CALLS,
                "清单内 HTTP 工具必须经 Dispatcher→core invoke（禁入探索提交链路）");
        assertEquals(0, ACT_CALLS.size(), "非内置动作零 acts 出站");
        assertEquals(1, STEP_BODIES.size(), "工具步同样留步进");
    }

    
    @Test
    void xwrite工具拒后循环继续() throws Exception {
        setAssembly(item(12, "kb_write", 1, true));
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "kb_write", "{\"q\":\"x\"}")));
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(0, INVOKE_CALLS.size(), "x-write 无票默认拒=零出站");
        assertEquals(0, ACT_CALLS.size(), "非内置名不进提交链路");
        assertEquals(1, STEP_BODIES.size(), "拒绝也算一步观察（回填后继续）");
        assertTrue(STEP_BODIES.get(0).contains("写语义") || STEP_BODIES.get(0).contains("x-write"),
                "stepResult 含拒因人话（模型可读），实际=" + STEP_BODIES.get(0));
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"), "循环继续至达成");
    }

    
    @Test
    void 无marker终答_判partial不空转() {
        scriptChain();
        script.add(finalAnswer("我没有任何可做的了"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(0, ACT_CALLS.size(), "零工具轮不出站");
        assertEquals(0, STEP_CALLS.size(), "无动作不记步进");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":4"),
                "无可行动作=partial(4)，实际=" + STATUS_BODIES.get(0));
    }

    
    @Test
    void 执行失败终态_人话观测回填不炸循环() throws Exception {
        taskJson = buildTaskJson(4, "定位器未匹配到提交按钮", "[]");
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "click-path",
                "{\"url\":\"http://a/1\",\"target\":{\"css\":\"#submit\"}}")));
        script.add(finalAnswer("GOAL_REACHED 已观察到失败现场"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(1, TASK_POLL_CALLS.size(), "失败终态同样停轮一次");
        JsonNode rec = MAPPER.readTree(STEP_BODIES.get(0));
        assertTrue(rec.path("screenshotKey").isNull(), "空 screenshots=键 null");
        JsonNode res = MAPPER.readTree(rec.path("stepResult").asText());
        assertEquals(false, res.path("ok").asBoolean(), "失败终态 ok=false");
        assertTrue(res.path("observation").asText().contains("定位器未匹配到提交按钮"),
                "errorDetail 人话进观测（模型可读纠偏），实际=" + res);
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"), "循环继续至达成");
    }

    
    @Test
    void 中途态轮询至终态_观测回填() throws Exception {
        TASK_JSON_QUEUE.add(buildTaskJson(2, null, "[]"));
        TASK_JSON_QUEUE.add(buildTaskJson(3, null, "[\"1/explore/202609/shot-9002.png\"]"));
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot", "{\"url\":\"http://a/1\"}")));
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        assertEquals(2, TASK_POLL_CALLS.size(), "执行中→成功=恰两轮轮询后停");
        JsonNode rec = MAPPER.readTree(STEP_BODIES.get(0));
        assertEquals("1/explore/202609/shot-9002.png", rec.path("screenshotKey").asText(null),
                "终态帧截图键回填（非中途空帧）");
    }

    
    @Test
    void 空工具清单仍携带内置探索动作声明() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session(null), ACTOR_ID);

        ChatCompletionRequest first = sentRequests.get(0);
        assertNotNull(first.tools(), "探索循环请求必须携带工具声明（内置四件兜底）");
        List<String> names = first.tools().stream()
                .map(t -> t.function().name()).toList();
        assertTrue(names.containsAll(List.of("page-snapshot", "click-path",
                        "input-path", "assert", "element-candidates")),
                "内置探索动作族必须在列（含页面探查采集），实际=" + names);
    }

    
    @Test
    void 首轮请求含目标宣告契约位() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session(null), ACTOR_ID);

        ChatCompletionRequest first = sentRequests.get(0);
        assertEquals("route-test-model", first.model(),
                "首轮请求必须携路由模型名（K9 路由= AiGen 同构），实际=" + first.model());
        String system = first.messages().get(0).content();
        assertTrue(system.contains("GOAL_REACHED"),
                "system 必须宣告达成契约位，实际=" + system);
        assertTrue(first.messages().get(1).content().contains("摸清下单流程"),
                "user 消息带目标描述");
    }


 
    private static ExploreSessionBrief mobileSession(String budgetSnapshot) {
        return new ExploreSessionBrief(SESSION_ID, PROJECT_ID, "漫游下单流程找崩溃",
                "app://com.demo.shop", 1, 0, 0, 0L, null, ACTOR_ID, budgetSnapshot,
                3L, "acct-buyer");
    }

    
    private static String buildCrashTaskJson() {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stepNo", 1);
            entry.put("action", "app.tap");
            entry.put("status", "fail");
            entry.put("detail", "FATAL EXCEPTION: main 进程 com.demo.shop");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", 9001);
            data.put("status", 4);
            data.put("errorDetail", "应用崩溃（崩溃哨兵事件回传）");
            data.put("stepsTimeline", MAPPER.writeValueAsString(List.of(entry)));
            data.put("screenshots", "[]");
            return "{\"code\":\"0\",\"msg\":\"ok\",\"data\":"
                    + MAPPER.writeValueAsString(data) + "}";
        } catch (Exception e) {
            throw new IllegalStateException("崩溃桩组装失败", e);
        }
    }

    
    @Test
    void 移动形态发语义信封_不装引擎与设备() throws Exception {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "tree-snapshot", "{}")));
        script.add(finalAnswer("GOAL_REACHED 已见首页"));

        kernel().run(mobileSession("{\"noProgressLimit\":3,\"appauto\":{\"maxSteps\":60,"
                + "\"maxTokens\":1000000,\"maxDurationSeconds\":3600}}"), ACTOR_ID);

        assertEquals(1, ACT_CALLS.size(), "移动动作仍走 /acts 提交链路（零新通道）");
        JsonNode envelope = MAPPER.readTree(MAPPER.readTree(ACT_BODIES.get(0))
                .path("actionJson").asText());
        assertEquals("explore-act", envelope.path("kind").asText());
        assertEquals("mobile", envelope.path("target").asText(), "target=mobile 语义判别键");
        assertEquals("tree-snapshot", envelope.path("action").asText());
        assertTrue(envelope.path("engine").isMissingNode(),
                "engine 禁装（ai-service 无引擎目录访问口，禁猜引擎=core 富化件单点）");
        assertTrue(envelope.path("leaseRef").isMissingNode(), "leaseRef 禁装");
        assertTrue(envelope.path("url").isMissingNode(), "移动信封无 url 语义");
    }

    
    @Test
    void 移动形态systemPrompt含无障碍树漫游与凭据引用() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(mobileSession(null), ACTOR_ID);

        String system = sentRequests.get(0).messages().get(0).content();
        assertTrue(system.contains("无障碍树"), "移动形态=树驱动漫游说明，实际=" + system);
        for (String action : List.of("tree-snapshot", "tap", "back", "swipe", "home")) {
            assertTrue(system.contains(action), "漫游候选必须在 prompt，缺=" + action);
        }
        assertTrue(system.contains("GOAL_REACHED"), "达成契约位双形态同守");
        String user = sentRequests.get(0).messages().get(1).content();
        assertTrue(user.contains("com.demo.shop"), "user 消息带包名语境，实际=" + user);
        assertTrue(user.contains("acct-buyer") && !user.contains("密码"),
                "凭据只给 {{CRED:键}} 引用名禁明文（红线 4），实际=" + user);
    }

    
    @Test
    void 移动形态工具清单为漫游五枚() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(mobileSession(null), ACTOR_ID);

        List<String> names = sentRequests.get(0).tools().stream()
                .map(t -> t.function().name()).toList();
        assertTrue(names.containsAll(List.of("tree-snapshot", "tap", "back", "swipe", "home")),
                "漫游五枚必在列，实际=" + names);
        assertTrue(!names.contains("page-snapshot") && !names.contains("click-path"),
                "web 内置件不得混入移动清单，实际=" + names);
    }

    
    @Test
    void 移动token预算触线_budget_stop不再续跑() {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "tap",
                "{\"target\":{\"text\":\"下单\"}}")));
        script.add(withTools(new LlmToolCall("c2", "back", "{}")));
        kernel().run(mobileSession("{\"noProgressLimit\":99,\"appauto\":{\"maxSteps\":60,"
                + "\"maxTokens\":50,\"maxDurationSeconds\":3600}}"), ACTOR_ID);

        assertTrue(STATUS_BODIES.get(0).contains("\"status\":3"),
                "token 触线=budget-stop(3)，实际=" + STATUS_BODIES.get(0));
    }

    
    @Test
    void 留痕分线_移动与web各自function() {
        scriptChain();
        script.add(finalAnswer("GOAL_REACHED"));
        kernel().run(mobileSession(null), ACTOR_ID);
        verify(aiCallLog).insert(eq(PROJECT_ID), any(), any(), eq("explore-appauto"),
                anyInt(), anyInt(), anyLong());

        script.add(finalAnswer("GOAL_REACHED"));
        kernel().run(session(null), ACTOR_ID);
        verify(aiCallLog).insert(eq(PROJECT_ID), any(), any(), eq("explore"),
                anyInt(), anyInt(), anyLong());
    }

    
    @Test
    void 崩溃步骤序列装配进报告段() {
        scriptChain();
        TASK_JSON_QUEUE.add(buildCrashTaskJson());
        script.add(withTools(new LlmToolCall("c1", "tap",
                "{\"target\":{\"text\":\"下单\"}}")));
        script.add(finalAnswer("GOAL_REACHED 复现崩溃"));

        kernel().run(mobileSession(null), ACTOR_ID);

        String report = STATS_BODIES.get(0);
        assertTrue(report.contains("崩溃现场步骤序列"),
                "崩溃命中必出 IR 步序列段，实际=" + report);
        assertTrue(report.contains("app.tap"), "步序=app.* IR 动词可复制存用例，实际=" + report);
    }

    
    @Test
    void 黑名单动词拒提交零出站不炸循环() {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "uninstall", "{\"pkg\":\"a\"}")));
        script.add(withTools(new LlmToolCall("c2", "tap", "{}")));
        script.add(finalAnswer("GOAL_REACHED 已绕行"));

        kernel().run(mobileSession("{\"noProgressLimit\":99,\"appauto\":{\"maxSteps\":60,"
                + "\"maxTokens\":1000000,\"maxDurationSeconds\":3600}}"), ACTOR_ID);

        assertEquals(1, ACT_CALLS.size(), "越界动词不进提交链路，仅合法 tap 一步");
        assertTrue(ACT_BODIES.get(0).contains("tap"), "唯一提交=合法 tap 动作");
        assertTrue(!ACT_BODIES.get(0).contains("uninstall"), "黑名单动词零出站");
        assertEquals(0, INVOKE_CALLS.size(), "黑名单动词禁进 Dispatcher 外呼");
        assertEquals(1, STEP_BODIES.size(), "越界轮零出站（不执行=不写步不留证=关态零残留纪律）");
        assertTrue(STATUS_BODIES.get(0).contains("\"status\":2"),
                "拒后循环继续、合法步照常走，实际=" + STATUS_BODIES.get(0));
        assertEquals(1, TASK_POLL_CALLS.size(), "合法 tap 步仍正常提交+轮询");
    }

    
    @Test
    void web形态信封带url零漂移() throws Exception {
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "page-snapshot",
                "{\"url\":\"http://localhost:5173/list\"}")));
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(session("{\"maxSteps\":30,\"maxPages\":10,\"noProgressLimit\":3}"),
                ACTOR_ID);

        JsonNode envelope = MAPPER.readTree(MAPPER.readTree(ACT_BODIES.get(0))
                .path("actionJson").asText());
        assertEquals("http://localhost:5173/list", envelope.path("url").asText());
        assertTrue(envelope.path("target").isMissingNode(),
                "web 信封不装 target=core 富化件直通零冲击");
    }

    
    @Test
    void 移动形态空screenshots回退步行artifactKey截图键() throws Exception {
        taskJson = buildTaskJson(3, null, "[]",
                "testpike/1/9001/exec-artifact/202610/ab12cd34-screenshot_step-1.png", null);
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "tree-snapshot", "{}")));
        script.add(finalAnswer("GOAL_REACHED 已见首页"));

        kernel().run(mobileSession(null), ACTOR_ID);

        JsonNode rec = MAPPER.readTree(STEP_BODIES.get(0));
        assertEquals("testpike/1/9001/exec-artifact/202610/ab12cd34-screenshot_step-1.png",
                rec.path("screenshotKey").asText(null),
                "移动形态截图键=stepsTimeline 行 artifactKey 回退位，实际=" + rec);
    }

 
    @Test
    void 移动形态失败步回退evidence槽截图键() throws Exception {
        taskJson = buildTaskJson(4, "第 1 步 app.tap 失败：元素不可见", "[]", null,
                "testpike/1/9001/exec-artifact/202610/ff00aa11-screenshot_fail-1.png");
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "tap",
                "{\"target\":{\"text\":\"下单\"}}")));
        script.add(finalAnswer("GOAL_REACHED 已见失败现场"));

        kernel().run(mobileSession(null), ACTOR_ID);

        JsonNode rec = MAPPER.readTree(STEP_BODIES.get(0));
        assertEquals("testpike/1/9001/exec-artifact/202610/ff00aa11-screenshot_fail-1.png",
                rec.path("screenshotKey").asText(null),
                "失败步 evidence.screenshot 槽回退，实际=" + rec);
    }

    
    @Test
    void 回退位非截图种类键保持null() throws Exception {
        taskJson = buildTaskJson(3, null, "[]",
                "testpike/1/9001/exec-artifact/202610/920b2cc6-dom_snapshot_step-1-viewtree.json",
                null);
        scriptChain();
        script.add(withTools(new LlmToolCall("c1", "tree-snapshot", "{}")));
        script.add(finalAnswer("GOAL_REACHED"));

        kernel().run(mobileSession(null), ACTOR_ID);

        JsonNode rec = MAPPER.readTree(STEP_BODIES.get(0));
        assertTrue(rec.path("screenshotKey").isNull(),
                "非 screenshot 种类键不得入截图位，实际=" + rec);
    }
}
