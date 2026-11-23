package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmFunctionCallingTest {

    private static HttpServer server;
    private static LlmFunctionCalling calling;
    /** Last X-Operator-Id observed by the stub . */
    private static volatile String lastOperatorHeader;

    @BeforeAll
    static void startStub() throws IOException {
        server = CoreStubHttp.startServer(LlmFunctionCallingTest::handle);
        calling = CoreStubHttp.assemblyCaller(server);
    }

    @AfterAll
    static void stopStub() {
        CoreStubHttp.shutdown(server);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        lastOperatorHeader = exchange.getRequestHeaders().getFirst("X-Operator-Id");
        String body = switch (path) {
            case "/internal/tools/project/1/assembly" -> """
                    {"code":"0","msg":"ok","data":[
                      {"toolId":10,"name":"doc_guide","type":1,"execKind":null,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                      {"toolId":11,"name":"kb_search","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\",\\"properties\\":{\\"q\\":{\\"type\\":\\"string\\"}}}","timeoutMs":30000,"write":false},
                      {"toolId":12,"name":"write_back","type":2,"execKind":3,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":true}]}
                    """;
            case "/internal/tools/project/2/assembly" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}";
            case "/internal/tools/project/3/assembly" -> """
                    {"code":"0","msg":"ok","data":[
                      {"toolId":20,"name":"broken_schema","type":2,"execKind":1,
                       "inputSchemaJson":"not-json{{","timeoutMs":5000,"write":false},
                      {"toolId":21,"name":"good_tool","type":2,"execKind":2,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":8000,"write":false}]}
                    """;
            default -> "{\"code\":\"40401\",\"msg\":\"项目不存在\",\"data\":null}";
        };
        CoreStubHttp.respondRawJson(exchange, body);
    }


    @Test
    void 装配读取actor非空透传操作人头_null零透传() {
        // Actor-bearing call: the core five-way gate reads X-Operator-Id, so
        // the assembly request must carry it verbatim.
        calling.assemblePlan(1L, 88L);
        assertEquals("88", lastOperatorHeader, "actor 必须经 X-Operator-Id 透传 core");

        // Null actor keeps the legacy header-less seam (generation chain drift-free).
        calling.assemblePlan(1L, null);
        assertNull(lastOperatorHeader, "null actor=旧口径零透传（禁向 core 发空头）");
    }

    @Test
    void 装配_文档类工具筛除仅可执行进FC清单() {
        FcPlan plan = calling.assemblePlan(1L, null);
        assertEquals(2, plan.tools().size(), "type=1 文档类单点筛除（tick97 裁决）");
        assertEquals("kb_search", plan.tools().get(0).function().name());
        assertEquals("write_back", plan.tools().get(1).function().name());
    }

    @Test
    void 装配_parameters为JSON对象直挂非字符串化() throws Exception {
        FcPlan plan = calling.assemblePlan(1L, null);
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(plan.tools().get(0));
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\""),
                "OpenAI 契约 parameters 必须为对象（二次转义=供应商 400）：" + json);
        assertTrue(json.contains("\"type\":\"function\""), "两层结构 type 固定 function");
    }

    @Test
    void 装配_schema不可解析工具failClosed剔除() {
        FcPlan plan = calling.assemblePlan(3L, null);
        assertEquals(1, plan.tools().size(), "坏 schema 宁缺勿滥（带病下发=供应商 400 断整链）");
        assertEquals("good_tool", plan.tools().get(0).function().name());
    }

    @Test
    void 装配_空集返回EMPTY计划() {
        FcPlan plan = calling.assemblePlan(2L, null);
        assertTrue(plan.isEmpty(), "无启用工具=空计划（降级为普通补全，禁 tools:[] 下发）");
    }

    @Test
    void 装配_索引供Dispatcher路由含写语义与形态() {
        FcPlan plan = calling.assemblePlan(1L, null);
        assertEquals(11L, plan.index().get("kb_search").toolId());
        assertEquals(1, plan.index().get("kb_search").execKind());
        assertEquals(3, plan.index().get("write_back").execKind());
        assertTrue(plan.index().get("write_back").write(), "x-write 透传=包4 确认门开关");
        assertFalse(plan.index().containsKey("doc_guide"), "文档类不进索引（越权调用面收口）");
    }


    private static ChatCompletionRequest baseRequest() {
        return new ChatCompletionRequest("test-model",
                List.of(new ChatMessage("system", "你是测试助手"),
                        new ChatMessage("user", "查登录用例")),
                null, null);
    }

    @Test
    void 组装_空计划原请求零漂移非FC() {
        ChatCompletionRequest base = baseRequest();
        ChatCompletionRequest out = calling.decorate(base, FcPlan.EMPTY);
        assertNull(out.tools(), "空计划=普通补全（包2 零漂移语义：不挂工具不改消息）");
        assertNull(out.toolChoice());
        assertEquals(base.messages(), out.messages());
    }

    @Test
    void 组装_注入FC契约位与工具清单且tool_choice_auto() {
        FcPlan plan = calling.assemblePlan(1L, null);
        ChatCompletionRequest out = calling.decorate(baseRequest(), plan);
        String system = out.messages().get(0).content();
        assertTrue(system.startsWith("你是测试助手"), "原文保留");
        assertTrue(system.contains(LlmExtractMarkers.FC_TOOL_CALL), "契约位进 system");
        assertEquals(2, out.tools().size());
        assertEquals("auto", out.toolChoice());
        assertEquals("查登录用例", out.messages().get(1).content(), "非 system 消息原样");
    }

    @Test
    void 组装_已含契约位不重复注入() {
        ChatCompletionRequest base = new ChatCompletionRequest("test-model",
                List.of(new ChatMessage("system", "带标记 " + LlmExtractMarkers.FC_TOOL_CALL),
                        new ChatMessage("user", "x")),
                null, null);
        ChatCompletionRequest out = calling.decorate(base, calling.assemblePlan(1L, null));
        String system = out.messages().get(0).content();
        assertEquals(1, system.split(LlmExtractMarkers.FC_TOOL_CALL, -1).length - 1,
                "标记单点（重复注入=契约漂移）");
    }

    @Test
    void 组装_无system消息时前置标记消息() {
        ChatCompletionRequest base = new ChatCompletionRequest("test-model",
                List.of(new ChatMessage("user", "直接问")), null, null);
        ChatCompletionRequest out = calling.decorate(base, calling.assemblePlan(1L, null));
        assertEquals("system", out.messages().get(0).role());
        assertTrue(out.messages().get(0).content().contains(LlmExtractMarkers.FC_TOOL_CALL));
        assertEquals("user", out.messages().get(1).role());
        assertNull(out.tools().get(0).function().description(), "装配视图无描述列=null 不序列化");
    }
}
