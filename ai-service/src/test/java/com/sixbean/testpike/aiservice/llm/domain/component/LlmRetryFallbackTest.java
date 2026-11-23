package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatResponseFormat;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmRetryFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    
    private static HttpServer server;
    private static String healthyUrl;
    private static final AtomicInteger requests = new AtomicInteger();
    private static volatile int failFirst = 0;
    
    private static volatile String lastRequestModel = null;
    
    private static volatile String lastRequestBody = null;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", LlmRetryFallbackTest::handle);
        server.start();
        healthyUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void resetStub() {
        requests.set(0);
        failFirst = 0;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequestBody = reqBody;
        try {
            lastRequestModel = MAPPER.readTree(reqBody).path("model").asText(null);
        } catch (Exception ignore) {
            lastRequestModel = null;
        }
        int n = requests.incrementAndGet();
        if (n <= failFirst) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        LlmTestFixtures.respondJson(exchange, "降级成功");
    }

    private static ChatCompletionRequest request() {
        return new ChatCompletionRequest("m", List.of(new ChatMessage("user", "hi")), null, null);
    }

    private static LlmEndpointConfig healthyEndpoint(String provider) {
        return new LlmEndpointConfig(provider, "m", "sk-test", healthyUrl, "chain");
    }

    
    private static LlmEndpointConfig deadEndpoint() {
        return new LlmEndpointConfig("dead-provider", "m", "sk-x", "http://127.0.0.1:1/v1", "project");
    }


    @Test
    void it_llm_02_重试3次仍失败_不再第4次_错误含尝试时间线() {
        failFirst = Integer.MAX_VALUE;
        LlmRetryPolicy policy = new LlmRetryPolicy(3, 10, 500);
        LlmProviderFactory factory = LlmTestFixtures.realFactory(MAPPER);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                policy.execute(() -> factory.chatFor(request(), healthyEndpoint("only"),
                        Duration.ofSeconds(10))));

        assertEquals(3, requests.get(), "恰好 3 次请求，无第 4 次（IT-LLM-02 退避上限钉）");
        String msg = ex.getMessage();
        assertTrue(msg.contains("第1次") && msg.contains("第2次") && msg.contains("第3次"),
                "错误聚合含 3 次尝试时间线: " + msg);
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode(), "保留原始错误码语义");
    }

    @Test
    void it_llm_02_第2次重试成功_立即返回不再消耗() {
        failFirst = 1;
        LlmRetryPolicy policy = new LlmRetryPolicy(3, 10, 500);
        LlmProviderFactory factory = LlmTestFixtures.realFactory(MAPPER);

        LlmChatResult result = policy.execute(() ->
                factory.chatFor(request(), healthyEndpoint("only"), Duration.ofSeconds(10)));

        assertEquals("降级成功", result.content());
        assertEquals(2, requests.get(), "成功即返回，第 3 次不发");
    }


    @Test
    void it_llm_03_降级链全空_fail_closed_明确报错零HTTP调用() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of();
        LlmProperties p = LlmTestFixtures.realProps();
        p.setBaseUrl("");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(3, 10, 500));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                chain.chat(request(), 1L, null));
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode(), "50201 fail-closed");
        assertTrue(ex.getMessage().contains("未配置可用 LLM 供应商"), "人话: " + ex.getMessage());
        assertEquals(0, requests.get(), "禁拿空 baseUrl 发请求（不回退内存默认）");
    }


    @Test
    void tc_e7_02_主供应商故障_降级第二供应商成功_审计通知含原因() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "m",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = deadEndpointProject();
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        LlmChatResult result = chain.chat(request(), 42L,
                new LlmFallbackNotice(7L, 42L, "AI 生成任务#42"));

        assertEquals("降级成功", result.content(), "重试耗尽后换供应商成功（TC-E7-02）");
        assertEquals(1, gw.audits.size(), "降级事件入审计（E7 链完整性）");
        LlmAuditRequest audit = gw.audits.get(0);
        assertEquals("LLM_FALLBACK", audit.action());
        assertEquals(7L, audit.operatorId());
        assertEquals(42L, audit.projectId());
        String snapshot = String.valueOf(audit.before()) + String.valueOf(audit.after());
        assertTrue(snapshot.contains("dead-provider"), "审计含失败源供应商");
        assertTrue(snapshot.contains("standby-prov"), "审计含生效供应商");
        assertTrue(!snapshot.contains("sk-test"), "审计快照禁密钥明文（TC-E2-03）: " + snapshot);
        assertEquals(1, gw.notifications.size(), "站内信通知（降级原因人话）");
        assertTrue(gw.notifications.get(0).startsWith("AI_LLM_FALLBACK"));
        assertTrue(gw.notifications.get(0).contains("已自动切换"), "人话: " + gw.notifications.get(0));
    }

    private static LlmConfigBrief deadEndpointProject() {
        return new LlmConfigBrief(1L, "primary", "dead-provider", "m", "sk-x",
                "http://127.0.0.1:1/v1", "project");
    }

    // ── L4 attribution: the exhausted-chain failure text must keep every
    // provider's own reason.
    // L4 evidence 2026-11-06: chain = dead project endpoint then a rate-limiting
    // fallback. Only the LAST provider text was rethrown, so the dead-port I/O
    // signal vanished from the listener exception the dead-letter capture flattens
    // — the ledger classifier never saw a transport token and booked UNKNOWN forever.

    @Test
    void 全链耗尽_失败文本保留首位拒连信号不被末位掩盖() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "m",
                "sk-x", "http://127.0.0.1:2/v1", "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = deadEndpointProject();
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                chain.chat(request(), 42L, new LlmFallbackNotice(7L, 42L, "AI 提取任务#42")));

        String msg = ex.getMessage();
        assertTrue(msg.contains("I/O error on POST"),
                "聚合明细必须保留首位死端点的连接类信号（死信分型输入）: " + msg);
        assertTrue(msg.contains("dead-provider") && msg.contains("standby-prov"),
                "聚合明细点名每个失败供应商: " + msg);
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode(), "错误码沿用末位供应商语义零漂移");
    }

    @Test
    void 全链耗尽_单候选链路失败文本零漂移不追加聚合段() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of();
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = deadEndpointProject();
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                chain.chat(request(), 42L, new LlmFallbackNotice(7L, 42L, "AI 提取任务#42")));

        assertTrue(!ex.getMessage().contains("全链失败明细"),
                "单候选=原样上抛，禁加第二套文案（UI 消息零漂移）: " + ex.getMessage());
        assertEquals(BizErrorCode.AI_PROVIDER_ERROR, ex.getCode());
    }


    @Test
    void tc_e7_02_FC请求_降级候选端点重建保留tools与tool_choice() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "m",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = deadEndpointProject();
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ChatCompletionRequest fcRequest = new ChatCompletionRequest("m",
                List.of(new ChatMessage("user", "查一下")), null, null,
                List.of(new ChatToolDef(new ChatToolFunction("kb_search", "检索", null))),
                "auto");
        LlmChatResult result = chain.chat(fcRequest, 42L, null);

        assertEquals("降级成功", result.content());
        assertTrue(lastRequestBody != null && lastRequestBody.contains("\"tools\""),
                "降级候选端点请求体应含 tools: " + lastRequestBody);
        assertTrue(lastRequestBody.contains("\"tool_choice\":\"auto\""),
                "降级候选端点请求体应含 tool_choice: " + lastRequestBody);
    }

    @Test
    // Structured-decoding block (response_format) must survive the fallback
    // candidate rebuild exactly like the FC fields do: the legacy six-arg copy
    // cleared it once and the schema never reached the endpoint (live window
    // w14 proved it, same family as the ReActRunner per-turn rebuild).
    void fallbackCandidateRequestKeepsStructuredResponseFormatOnTheWire() {
        // Same fault injection as the FC passthrough case: dead primary, healthy standby.
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "m",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = deadEndpointProject();
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ChatCompletionRequest structured = request().withResponseFormat(
                ChatResponseFormat.jsonSchema("case_batch", true,
                        MAPPER.createObjectNode().put("type", "object")));
        LlmChatResult result = chain.chat(structured, 42L, null);

        assertEquals("降级成功", result.content());
        assertNotNull(lastRequestBody, "standby endpoint should have received a request body");
        assertTrue(lastRequestBody.contains("\"response_format\""),
                "降级候选端点请求体应含 response_format: " + lastRequestBody);
        assertTrue(lastRequestBody.contains("\"json_schema\"")
                        && lastRequestBody.contains("\"case_batch\""),
                "response_format 块应完整携带 schema 契约: " + lastRequestBody);
    }


    @Test
    void 显式钉模型_降级候选端点不换模型_请求体保持用户指定值() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "standby-model",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(1L, "primary", "dead-provider", "primary-model",
                "sk-x", "http://127.0.0.1:1/v1", "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ChatCompletionRequest pinned = new ChatCompletionRequest("user-pinned-model",
                List.of(new ChatMessage("user", "hi")), null, null);
        chain.chat(pinned, 42L, null, true);

        assertEquals("user-pinned-model", lastRequestModel,
                "降级候选端点请求体必须保持用户显式指定的模型（换=假模型被备端救活，TC-F2-02 破坏）");
    }

    @Test
    void 默认路由模型_降级候选端点仍换备端自有模型_TC_E7_02_语义保持() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "standby-model",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(1L, "primary", "dead-provider", "primary-model",
                "sk-x", "http://127.0.0.1:1/v1", "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ChatCompletionRequest routed = new ChatCompletionRequest("primary-model",
                List.of(new ChatMessage("user", "hi")), null, null);
        LlmChatResult result = chain.chat(routed, 42L, null);

        assertEquals("降级成功", result.content());
        assertEquals("standby-model", lastRequestModel,
                "默认路由降级候选端点换备端自有模型（TC-E7-02 v3.22 接线不动）");
    }

    @Test
    void tc_e7_03_全链耗尽_失败带人话错误_不无限重试() {
        failFirst = Integer.MAX_VALUE;
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "b2", "prov-b", "m", "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(1L, "b1", "prov-a", "m", "sk-test", healthyUrl, "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                chain.chat(request(), 42L, null));
        assertEquals(2, requests.get(), "退避上限钉死，失败即聚合抛出: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("供应商"), "人话错误: " + ex.getMessage());
    }


    @Test
    void chatHandle_降级后_返回实际生效端点而非主端点() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(new LlmConfigBrief(9L, "standby", "standby-prov", "standby-model",
                "sk-test", healthyUrl, "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(1L, "primary", "dead-provider", "primary-model",
                "sk-x", "http://127.0.0.1:1/v1", "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        LlmChatHandle handle = chain.chatHandle(request(), 42L,
                new LlmFallbackNotice(7L, 42L, "AI 生成任务#42"));

        assertEquals("standby-prov", handle.activeEndpoint().provider(),
                "生效端点=降级后的第二供应商（主端点已故障）");
        assertEquals("standby-model", handle.activeEndpoint().modelName(),
                "modelName 取实际生效端点（markModel 回填不失真）");
        assertEquals("standby-model", lastRequestModel,
                "降级候选端点请求体 model 须换为生效端点自有模型（主模型名打到异构供应商必败）");
        assertEquals("降级成功", handle.result().content());
    }

    @Test
    void chatHandle_主端点健康_返回主端点() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of();
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(1L, "primary", "primary-prov", "primary-model",
                "sk-test", healthyUrl, "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(LlmTestFixtures.realFactory(MAPPER), router, gw,
                new LlmRetryPolicy(2, 10, 500));

        LlmChatHandle handle = chain.chatHandle(request(), 42L, null);

        assertEquals("primary-prov", handle.activeEndpoint().provider(),
                "无降级=生效端点即主路由端点");
        assertEquals(0, gw.audits.size(), "成功路径零审计开销");
    }


    @Test
    void routeChain_主端点优先_清单去重_空baseUrl剔除() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        gw.briefs = List.of(
                new LlmConfigBrief(1L, "same-as-primary", "p1", "m", "k", healthyUrl, "default"),
                new LlmConfigBrief(2L, "other", "p2", "m2", "k2", "http://127.0.0.1:2/v1", "default"),
                new LlmConfigBrief(3L, "blank-url", "p3", "m3", "k3", "", "default"));
        LlmProperties p = LlmTestFixtures.realProps();
        gw.projectBrief = new LlmConfigBrief(5L, "primary", "primary-prov", "m", "k",
                healthyUrl, "project");
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);

        List<LlmEndpointConfig> chain = router.routeChain(1L);
        assertEquals(2, chain.size(), "主端点 + 1 个不重复条目（同 baseUrl 去重、空 baseUrl 剔除）");
        assertEquals("primary-prov", chain.get(0).provider(), "主路由端点排首位");
        assertEquals("p2", chain.get(1).provider());
    }


    @Test
    void it_llm_04_K8超时预算_热更即生效_非法值回退配置文件() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        LlmProperties p = new LlmProperties();
        p.setTimeoutSeconds(45);
        LlmTimeoutResolver resolver = new LlmTimeoutResolver(gw, p);

        gw.timeoutConfigValue = "10";
        assertEquals(Duration.ofSeconds(10), resolver.resolve(), "K8 值生效（不重启即读新值）");
        gw.timeoutConfigValue = "60";
        assertEquals(Duration.ofSeconds(60), resolver.resolve(), "改值后下次调用即用=热更");
        gw.timeoutConfigValue = "abc";
        assertEquals(Duration.ofSeconds(45), resolver.resolve(), "非法值回退配置文件，不炸链路");
        gw.timeoutConfigValue = null;
        assertEquals(Duration.ofSeconds(45), resolver.resolve(), "无 K8 键回退配置文件");
    }

    @Test
    void mock模式_降级链直通本地替身_零HTTP() {
        GatewayRecordingStub gw = new GatewayRecordingStub();
        LlmProperties p = new LlmProperties();
        LlmProviderFactory mockFactory = new LlmProviderFactory(new MockLlmProvider("mm"), p, MAPPER);
        ProjectLlmRouter router = new ProjectLlmRouter(gw, p);
        LlmFallbackChain chain = new LlmFallbackChain(mockFactory, router, gw,
                new LlmRetryPolicy(3, 10, 500));

        LlmChatResult result = chain.chat(request(), 1L, null);
        assertTrue(result.content().startsWith("【mock 生成】"), "本地开发替身直通（IT-GEN-17/18）");
        assertEquals(0, requests.get());
    }
}
