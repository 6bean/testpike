package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.mcp.domain.component.FakeMcpToolSession;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag;
import com.sixbean.testpike.aiservice.mcp.domain.component.ToolInvocationRecorder;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class ToolDispatcherTest {

    private static final Long ACTOR_ID = 7L;
    private static final Long TASK_ID = 42L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger invokeHits = new AtomicInteger();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastOperatorHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        server = CoreStubHttp.startServer(ToolDispatcherTest::handle);
        baseUrl = CoreStubHttp.baseUrl(server);
    }

    @AfterAll
    static void stopStub() {
        CoreStubHttp.shutdown(server);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        invokeHits.incrementAndGet();
        lastOperatorHeader.set(exchange.getRequestHeaders().getFirst("X-Operator-Id"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = switch (exchange.getRequestURI().getPath()) {
            case "/internal/tools/11/invoke", "/internal/tools/12/invoke" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"toolId\":11,\"toolName\":\"kb_http\","
                            + "\"success\":true,\"output\":\"{\\\"data\\\":1}\",\"errorCategory\":null,"
                            + "\"errorCode\":null,\"errorMessage\":null,\"durationMs\":120}}";
            case "/internal/tools/14/invoke" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"toolId\":14,\"toolName\":\"write_tool\","
                            + "\"success\":true,\"output\":\"{\\\"written\\\":1}\",\"errorCategory\":null,"
                            + "\"errorCode\":null,\"errorMessage\":null,\"durationMs\":80}}";
            case "/internal/tools/17/invoke" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"toolId\":17,\"toolName\":\"slow_http\","
                            + "\"success\":false,\"output\":null,\"errorCategory\":\"timeout\","
                            + "\"errorCode\":50401,\"errorMessage\":\"执行超时（预算 5000ms）\","
                            + "\"durationMs\":5000}}";
            case "/internal/tools/18/invoke" ->
                    "{\"code\":\"40001\",\"msg\":\"工具未发布（当前：草稿），已拒绝执行\",\"data\":null}";
            default -> "{\"code\":\"40401\",\"msg\":\"工具不存在\",\"data\":null}";
        };
        CoreStubHttp.respondRawJson(exchange, body);
    }

    private static void resetStub() {
        invokeHits.set(0);
        lastBody.set(null);
        lastOperatorHeader.set(null);
        lastPath.set(null);
    }


    private static ToolFcAssemblyRemoteItem item(long id, String name, int type, Integer kind, boolean write) {
        return new ToolFcAssemblyRemoteItem(id, name, type, kind, "{\"type\":\"object\"}", 5000, write);
    }

    private static FcPlan plan(ToolFcAssemblyRemoteItem... items) {
        Map<String, ToolFcAssemblyRemoteItem> index = new LinkedHashMap<>();
        List<ChatToolDef> defs = new java.util.ArrayList<>();
        for (ToolFcAssemblyRemoteItem i : items) {
            index.put(i.name(), i);
            defs.add(new ChatToolDef(new ChatToolFunction(i.name(), null,
                    MAPPER.createObjectNode())));
        }
        return new FcPlan(List.copyOf(defs), Map.copyOf(index));
    }

    private static final FcPlan PLAN = plan(
            item(11, "kb_http", 2, 1, false),
            item(12, "py_tool", 2, 2, false),
            item(13, "mcp_bridge", 2, 3, false),
            item(14, "write_tool", 2, 1, true),
            item(17, "slow_http", 2, 1, false),
            item(18, "draft_tool", 2, 1, false));

    private ToolDispatcher dispatcher() {
        return new ToolDispatcher(new CoreGateway(baseUrl), MAPPER);
    }

    private static LlmToolCall call(String name, String argumentsJson) {
        return new LlmToolCall("call-1", name, argumentsJson);
    }

    
    @Test
    void http形态经core_invoke端点执行() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("kb_http", "{\"q\":\"登录\"}"), ACTOR_ID, TASK_ID, null);
        assertTrue(outcome.success());
        assertEquals("/internal/tools/11/invoke", lastPath.get());
        assertEquals("7", lastOperatorHeader.get(), "X-Operator-Id 透传任务发起人");
        assertTrue(lastBody.get().contains("\"taskId\":42"), "body taskId 归因");
        assertTrue(lastBody.get().contains("\\\"q\\\":\\\"登录\\\""), "arguments 原文直传禁二次序列化");
        assertEquals("{\"data\":1}", outcome.content());
        assertEquals("kb_http", outcome.toolName());
    }

    
    @Test
    void executor形态同端点路由() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("py_tool", "{}"), ACTOR_ID, TASK_ID, null);
        assertTrue(outcome.success());
        assertEquals("/internal/tools/12/invoke", lastPath.get());
    }

    
    @Test
    void 执行失败分型人话透传() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("slow_http", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("timeout"), "分类透传");
        assertTrue(outcome.content().contains("执行超时（预算 5000ms）"), "人话原因透传");
    }

    
    @Test
    void core守卫拒人话透传() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("draft_tool", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("未发布"), "fail-closed 人话进 tool 消息");
    }

    
    @Test
    void 幻觉工具名越权拒零出站() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("ghost_tool", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("不在项目启用清单"));
        assertEquals(0, invokeHits.get(), "零出站");
    }

    
    @Test
    void 参数体非法JSON分型拒() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("kb_http", "not-json{{"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("参数体不是合法 JSON"));
        assertEquals(0, invokeHits.get(), "零出站");
    }

    
    @Test
    void 写语义默认拒() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("write_tool", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("写语义"));
        assertEquals(0, invokeHits.get());
    }

    
    @Test
    void 写语义带确认票经core链路透传() {
        resetStub();
        ToolCallOutcome outcome = dispatcher()
                .dispatch(PLAN, call("write_tool", "{}"), ACTOR_ID, TASK_ID, null, "tk-abc");
        assertTrue(outcome.success(), outcome.content());
        assertEquals("/internal/tools/14/invoke", lastPath.get());
        assertTrue(lastBody.get().contains("\"confirmToken\":\"tk-abc\""),
                "confirmToken 进 body 透传 core: " + lastBody.get());
    }

    
    @Test
    void 桥链路写语义带票仍拒() {
        McpTaskToolBag bag = bagWith(8, new java.util.HashMap<>());
        ToolDispatcher d = new ToolDispatcher(mock(CoreGateway.class), MAPPER);
        FcPlan p = plan(item(15, "mcp_write", 2, 3, true));
        ToolCallOutcome outcome = d.dispatch(p, call("mcp_write", "{}"),
                ACTOR_ID, TASK_ID, bag, "tk-abc");
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("写语义"), outcome.content());
        assertEquals(0, bag.usedCount(), "桥链路写语义拒零计数");
    }

    
    @Test
    void 文档类混入防御拒() {
        resetStub();
        FcPlan p = plan(item(21, "doc_mix", 1, null, false));
        ToolCallOutcome outcome = dispatcher().dispatch(p, call("doc_mix", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertEquals(0, invokeHits.get());
    }


    private static McpAssemblyRemoteServer bridgeServer() {
        return new McpAssemblyRemoteServer(9L, "bridge", "http://127.0.0.1:1/mcp", 2, null,
                List.of("mcp_bridge"));
    }

    
    private McpTaskToolBag bagWith(int budget, Map<Long, FakeMcpToolSession> fakes) {
        CoreGateway gateway = mock(CoreGateway.class);
        when(gateway.getSysConfig(McpClientPool.CALL_BUDGET_KEY)).thenReturn(String.valueOf(budget));
        when(gateway.getMcpAssemblyServers(anyLong())).thenReturn(List.of(bridgeServer()));
        McpClientPool pool = new McpClientPool(gateway, new ToolInvocationRecorder(gateway), s -> {
            FakeMcpToolSession f = new FakeMcpToolSession(s, null);
            fakes.put(s.serverId(), f);
            return f;
        });
        return pool.openTask(TASK_ID, 3L);
    }

    
    @Test
    void mcp桥链路执行与留痕双引用() {
        resetStub();
        Map<Long, FakeMcpToolSession> fakes = new java.util.HashMap<>();
        McpTaskToolBag bag = bagWith(8, fakes);
        ToolDispatcher dispatcher = new ToolDispatcher(mock(CoreGateway.class), MAPPER);
        ToolCallOutcome outcome = dispatcher.dispatch(PLAN,
                call("mcp_bridge", "{\"q\":\"检索\"}"), ACTOR_ID, TASK_ID, bag);
        assertTrue(outcome.success());
        assertEquals(0, invokeHits.get(), "MCP 链路不经 core invoke（本地会话直调）");
        assertEquals("{\"q\":\"检索\"}", fakes.get(9L).lastArguments(), "arguments 原文直传白名单链路");
        assertEquals("{\"q\":\"检索\"}", outcome.content(), "工具返回文本进 tool 消息回填");
        List<McpInvocationRemoteInput> traces = bag.invocationTraces();
        assertEquals(1, traces.size());
        McpInvocationRemoteInput t = traces.get(0);
        assertEquals("ai", t.caller(), "TC-U-03 caller=ai");
        assertEquals(TASK_ID, t.taskId());
        assertEquals(13L, t.toolId(), "V42 裁定②：MCP_BRIDGE=tool_id 有");
        assertEquals(9L, t.serverId(), "V42 裁定②：MCP_BRIDGE=server_id 有（双引用）");
        assertEquals("mcp_bridge", t.toolName());
        assertEquals("{\"q\":\"检索\"}", t.digest(), "digest=arguments 原文");
        assertEquals(1, t.success());
    }

    
    @Test
    void mcp桥勾选集外拒() {
        McpTaskToolBag bag = bagWith(8, new java.util.HashMap<>());
        ToolDispatcher dispatcher = new ToolDispatcher(mock(CoreGateway.class), MAPPER);
        FcPlan p = plan(item(19, "not_checked", 2, 3, false));
        ToolCallOutcome outcome = dispatcher.dispatch(p,
                call("not_checked", "{}"), ACTOR_ID, TASK_ID, bag);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("白名单"));
        assertEquals(0, bag.usedCount(), "集外零调用不占预算");
    }

    
    @Test
    void mcp桥共享预算熔断() {
        McpTaskToolBag bag = bagWith(1, new java.util.HashMap<>());
        ToolDispatcher dispatcher = new ToolDispatcher(mock(CoreGateway.class), MAPPER);
        ToolCallOutcome first = dispatcher.dispatch(PLAN, call("mcp_bridge", "{}"), ACTOR_ID, TASK_ID, bag);
        ToolCallOutcome second = dispatcher.dispatch(PLAN, call("mcp_bridge", "{}"), ACTOR_ID, TASK_ID, bag);
        assertTrue(first.success());
        assertFalse(second.success(), "第二次超预算必拒");
        assertTrue(second.content().contains("超预算"));
        assertTrue(bag.budgetExceeded());
    }

    
    @Test
    void mcp桥无工具袋防御拒() {
        resetStub();
        ToolCallOutcome outcome = dispatcher().dispatch(PLAN,
                call("mcp_bridge", "{}"), ACTOR_ID, TASK_ID, null);
        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("工具袋"));
        assertEquals(0, invokeHits.get());
    }
}
